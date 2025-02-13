(ns metabase.models.collection.graph
  "Code for generating and updating the Collection permissions graph. See [[metabase.models.permissions]] for more
  details and for the code for generating and updating the *data* permissions graph."
  (:require
   [clojure.data :as data]
   [metabase.db.query :as mdb.query]
   [metabase.models.collection :as collection :refer [Collection]]
   [metabase.models.collection-permission-graph-revision
    :as c-perm-revision
    :refer [CollectionPermissionGraphRevision]]
   [metabase.models.permissions :as perms :refer [Permissions]]
   [metabase.models.permissions-group :as perms-group :as perms-group :refer [PermissionsGroup]]
   [metabase.public-settings.premium-features :refer [defenterprise]]
   [metabase.util :as u]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               PERMISSIONS GRAPH                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

;;; ---------------------------------------------------- Schemas -----------------------------------------------------

(def ^:private CollectionPermissions
  [:enum :write :read :none])

(def ^:private GroupPermissionsGraph
  "collection-id -> status"
  ; when doing a delta between old graph and new graph root won't always
  ; be present, which is why it's *optional*
  [:map-of [:or [:= :root] ms/PositiveInt] CollectionPermissions])

(def ^:private PermissionsGraph
  [:map {:closed true}
   [:revision :int]
   [:groups   [:map-of ms/PositiveInt GroupPermissionsGraph]]])

;;; -------------------------------------------------- Fetch Graph ---------------------------------------------------

(defn- group-id->permissions-set []
  (into {} (for [[group-id perms] (group-by :group_id (t2/select Permissions))]
             {group-id (set (map :object perms))})))

(mu/defn ^:private perms-type-for-collection :- CollectionPermissions
  [permissions-set collection-or-id]
  (cond
    (perms/set-has-full-permissions? permissions-set (perms/collection-readwrite-path collection-or-id)) :write
    (perms/set-has-full-permissions? permissions-set (perms/collection-read-path collection-or-id))      :read
    :else                                                                                                :none))

(mu/defn ^:private group-permissions-graph :- GroupPermissionsGraph
  "Return the permissions graph for a single group having `permissions-set`."
  [collection-namespace permissions-set collection-ids]
  (into
   {:root (perms-type-for-collection permissions-set (assoc collection/root-collection :namespace collection-namespace))}
   (for [collection-id collection-ids]
     {collection-id (perms-type-for-collection permissions-set collection-id)})))

(mu/defn ^:private non-personal-collection-ids :- [:set ms/PositiveInt]
  "Return a set of IDs of all Collections that are neither Personal Collections nor descendants of Personal
  Collections (i.e., things that you can set Permissions for, and that should go in the graph.)"
  [collection-namespace :- [:maybe ms/KeywordOrString]]
  (let [personal-collection-ids (t2/select-pks-set Collection :personal_owner_id [:not= nil])
        honeysql-form           {:select [[:id :id]]
                                 :from   [:collection]
                                 :where  (into [:and
                                                [:= :namespace (u/qualified-name collection-namespace)]
                                                [:= :personal_owner_id nil]]
                                               (for [collection-id personal-collection-ids]
                                                 [:not [:like :location (h2x/literal (format "/%d/%%" collection-id))]]))}]
    (set (map :id (mdb.query/query honeysql-form)))))

(defn- collection-permission-graph
  "Return the permission graph for the collections with id in `collection-ids` and the root collection."
  ([collection-ids] (collection-permission-graph collection-ids nil))
  ([collection-ids collection-namespace]
   (let [group-id->perms (group-id->permissions-set)]
     {:revision (c-perm-revision/latest-id)
      :groups   (into {} (for [group-id (t2/select-pks-set PermissionsGroup)]
                           {group-id (group-permissions-graph collection-namespace
                                                              (group-id->perms group-id)
                                                              collection-ids)}))})))

(defn- modify-instance-analytics-for-admins
  "In the graph, override the instance analytics collection within the admin group to read."
  [graph]
  (let [admin-group-id      (:id (perms-group/admin))
        audit-collection-id (:id (perms/default-audit-collection))]
    (if (nil? audit-collection-id)
      graph
      (assoc-in graph [:groups admin-group-id audit-collection-id] :read))))

(mu/defn graph :- PermissionsGraph
  "Fetch a graph representing the current permissions status for every group and all permissioned collections. This
  works just like the function of the same name in `metabase.models.permissions`; see also the documentation for that
  function.

  The graph is restricted to a given namespace by the optional `collection-namespace` param; by default, `nil`, which
  restricts it to the 'default' namespace containing normal Card/Dashboard/Pulse Collections.

  Note: All Collections are returned at the same level of the 'graph', regardless of how the Collection hierarchy is
  structured. Collections do not inherit permissions from ancestor Collections in the same way data permissions are
  inherited (e.g. full `:read` perms for a Database implies `:read` perms for all its schemas); a 'child' object (e.g.
  schema) *cannot* have more restrictive permissions than its parent (e.g. Database). Child Collections *can* have
  more restrictive permissions than their parent."
  ([]
   (graph nil))

  ([collection-namespace :- [:maybe ms/KeywordOrString]]
   (t2/with-transaction [_conn]
     (-> collection-namespace
         non-personal-collection-ids
         (collection-permission-graph collection-namespace)
         modify-instance-analytics-for-admins))))

;;; -------------------------------------------------- Update Graph --------------------------------------------------

(mu/defn ^:private update-collection-permissions!
  "Update the permissions for group ID with `group-id` on collection with ID
  `collection-id` in the optional `collection-namespace` to `new-collection-perms`."
  [collection-namespace :- [:maybe ms/KeywordOrString]
   group-id             :- ms/PositiveInt
   collection-id        :- [:or [:= :root] ms/PositiveInt]
   new-collection-perms :- CollectionPermissions]
  (let [collection-id (if (= collection-id :root)
                        (assoc collection/root-collection :namespace collection-namespace)
                        collection-id)]
    ;; remove whatever entry is already there (if any) and add a new entry if applicable
    (perms/revoke-collection-permissions! group-id collection-id)
    (case new-collection-perms
      :write (perms/grant-collection-readwrite-permissions! group-id collection-id)
      :read  (perms/grant-collection-read-permissions! group-id collection-id)
      :none  nil)))

(mu/defn ^:private update-group-permissions!
  [collection-namespace :- [:maybe ms/KeywordOrString]
   group-id             :- ms/PositiveInt
   new-group-perms      :- GroupPermissionsGraph]
  (doseq [[collection-id new-perms] new-group-perms]
    (update-collection-permissions! collection-namespace group-id collection-id new-perms)))

(defenterprise update-audit-collection-permissions!
  "OSS implementation of `audit-db/update-audit-collection-permissions!`, which is an enterprise feature, so does nothing in the OSS
  version."
  metabase-enterprise.audit-app.permissions [_ _] ::noop)

(mu/defn update-graph!
  "Update the Collections permissions graph for Collections of `collection-namespace` (default `nil`, the 'default'
  namespace). This works just like [[metabase.models.permission/update-data-perms-graph!]], but for Collections;
  refer to that function's extensive documentation to get a sense for how this works."
  ([new-graph]
   (update-graph! nil new-graph))

  ([collection-namespace :- [:maybe ms/KeywordOrString], new-graph :- PermissionsGraph]
   (let [old-graph          (graph collection-namespace)
         old-perms          (:groups old-graph)
         new-perms          (:groups new-graph)
         ;; filter out any groups not in the old graph
         new-perms          (select-keys new-perms (keys old-perms))
         ;; filter out any collections not in the old graph
         new-perms          (into {} (for [[group-id collection-id->perms] new-perms]
                                      [group-id (select-keys collection-id->perms (keys (get old-perms group-id)))]))
         [diff-old changes] (data/diff old-perms new-perms)]
     (perms/log-permissions-changes diff-old changes)
     (perms/check-revision-numbers old-graph new-graph)
     (when (seq changes)
       (t2/with-transaction [_conn]
         (doseq [[group-id changes] changes]
           (update-audit-collection-permissions! group-id changes)
           (update-group-permissions! collection-namespace group-id changes))
         (perms/save-perms-revision! CollectionPermissionGraphRevision (:revision old-graph)
                                      (assoc old-graph :namespace collection-namespace) changes))))))
