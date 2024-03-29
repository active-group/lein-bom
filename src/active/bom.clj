(ns active.bom
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [clojure.data.xml :as xml]
            [clojure.data.json :as json]))

(defn mk-tmp-dir!
  "Creates a unique temporary directory on the filesystem. Typically in /tmp on
  *NIX systems. Returns a File object pointing to the new directory. Raises an
  exception if the directory couldn't be created after 10000 tries."
  ;; Source: https://gist.github.com/samaaron/1398198
  []
  (let [base-dir     (io/file (System/getProperty "java.io.tmpdir"))
        base-name    (str (System/currentTimeMillis) "-" (long (rand 1000000000)) "-")
        tmp-base     (string/join "/" [base-dir base-name])
        max-attempts 10000]
    (loop [num-attempts 1]
      (if (= num-attempts max-attempts)
        (throw (Exception. (str "Failed to create temporary directory after " max-attempts " attempts.")))
        (let [tmp-dir-name (str tmp-base num-attempts)
              tmp-dir      (io/file tmp-dir-name)]
          (if (.mkdir tmp-dir)
            tmp-dir
            (recur (inc num-attempts))))))))

(defn- make-dependency-tree
  "Fetch all dependencies for the project and put them into a new m2 repository.
  Returns the dependency tree."
  [deps repos offline?]
  ;; Source: https://github.com/joodie/lein-deps-tree/blob/master/src/leiningen/deps_tree.clj
  (let [local-repo (mk-tmp-dir!)]
    [local-repo (aether/dependency-hierarchy
                 deps
                 (aether/resolve-dependencies
                  :local-repo local-repo
                  :offline? offline?
                  :repositories (into {} repos)
                  :coordinates deps
                  :transfer-listener :stdout))]))

(defn dep->pom-name
  [[dep version & more]]
  (let [dep* (str (last (string/split (str dep) #"/")))]
    (str dep* "-" version ".pom")))

(defn- match-pom
  [dep all-poms]
  (let [dep-s (dep->pom-name dep)]
    (first (filter (fn [pom] (string/ends-with? pom dep-s)) all-poms))))

(defn- dependencies->poms
  [deps direct-dependencies all-poms ignore]
  (reduce
   (fn [acc [top-level transitive]]
     (let [pom (match-pom top-level all-poms)]
       (cond
         (nil? pom)
         (do
           (println "ERROR: Could not find pom-file for dependency" (pr-str top-level))
           acc)  ; We didn't find a pom for this entry.

         (contains? ignore (first top-level))  ; If we ignore the top-level, ignore its children as well.
         acc

         :else
         (concat acc
                 [[(contains? direct-dependencies (first top-level)) pom]]
                 (dependencies->poms transitive
                                     direct-dependencies
                                     all-poms
                                     ignore)))))
   [] deps))

;; https://github.com/package-url/purl-spec
;; Schema: scheme:type/namespace/name@version?qualifiers#subpath

;; this is the URL scheme with the constant value of "pkg".
(s/def ::string (s/and string? not-empty))
(s/def ::purl-scheme #{"pkg"})
;; the package "type" or package "protocol" such as maven, npm, nuget, gem, pypi, etc. Required.
(s/def ::purl-type ::string)
;; some name prefix such as a Maven groupid, a Docker image owner, a GitHub user or organization. Optional and type-specific.
(s/def ::purl-namespace ::string)
;; the name of the package. Required.
(s/def ::purl-name ::string)
;; the version of the package. Optional.
(s/def ::purl-version ::string)
;; extra qualifying data for a package such as an OS, architecture, a distro, etc. Optional and type-specific.
(s/def ::purl-qualifiers (s/map-of keyword? any?))  ; pairs like {:repository_url "gcr.io"}
;; extra subpath within a package, relative to the package root. Optional.
(s/def ::purl-subpath ::string)

(s/def ::purl (s/keys :req-un [::purl-scheme ::purl-type ::purl-namespace ::purl-name]
                      :opt-un [::purl-version ::purl-qualifiers ::purl-subpath]))

(s/fdef make-purl
  :args (s/cat :group-id ::purl-namespace :artifact-id ::purl-name :version ::purl-version)
  :ret ::purl)
(defn make-purl
  [group-id artifact-id version]
  (let [purl-scheme     "pkg"   ; constant according to spec
        purl-type       "maven" ; constant in our specific case
        purl-namespace  group-id
        purl-name       artifact-id
        purl-version    version
        purl-qualifiers {}
        purl-subpath    nil]
    (str purl-scheme ":" purl-type "/" purl-namespace "/" purl-name "@" purl-version)))

(defn- repo-url-as-source-url
  [repo-url]
  (when repo-url
    (try
      (let [split-regex                 #"(/|:|@)"
            [_ _ _ site group artifact] (->> (string/split repo-url split-regex)
                                             (filter not-empty))
            artifact                    (butlast (string/split artifact #"\."))]
        (str "https://" site "/" group "/" (string/join "." artifact)))
      (catch Exception _
        nil))))

(defn- pom->bom
  [[direct-dependency? pom]]
  (letfn [(elems->vec [[a b]]
            (let [[a b] (if (= :name (:tag a)) [a b] [b a])]
              [(first (:content a)) (first (:content b))]))]
    (let [content               (filter map? (:content pom))
          get-tag-value         (fn [tag content]
                                  (filter (comp (partial = tag) keyword name :tag) content))
          get-tag-value-content (fn [tag content]
                                  (:content (first (get-tag-value tag content))))
          license               (->> content
                                     (get-tag-value :licenses)
                                     (get-tag-value-content :licenses)
                                     (filter map?)
                                     (get-tag-value-content :license)
                                     (filter map?))
          artifact-id           (first (get-tag-value-content :artifactId content))
          group-id              (first (get-tag-value-content :groupId content))
          group-id              (or group-id artifact-id)
          project-url           (first (get-tag-value-content :url content))
          version               (first (get-tag-value-content :version content))
          repo-url              (->> content
                                     (get-tag-value-content :scm)
                                     (filter map?)
                                     (get-tag-value-content :connection)
                                     first)]
      (->> {:origin           "external"
            :directDependency direct-dependency?
            :usageType        "COMPONENT_DYNAMIC_LIBRARY"
            :artifactId       artifact-id
            ;; NOTE Some package do not specify a group-id. Maven convention tells us
            ;;      to use the artifact-id in these cases.
            :namespace        group-id
            :purl             (make-purl group-id artifact-id version)
            :sources          {:combined
                               (let [url (or project-url
                                             (repo-url-as-source-url repo-url))]
                                 {:absolutePath url
                                  :downloadUrl url})}
            :version          version
            :licenses         {:main [{:name (first (get-tag-value-content :name license))
                                       :url  (first (get-tag-value-content :url license))}]}
            :repoUrl          repo-url
            :description      (first (get-tag-value-content :description content))}
           (filter (comp some? second))
           (into {})))))

(defn- collect-all-poms
  "Returns a set of all files in under `root` that end in .pom."
  [root]
  (->> (file-seq (io/file root))
       (filter (fn [^java.io.File file] (string/ends-with? (.getName file) ".pom")))
       (mapv (fn [^java.io.File file] (.getPath file)))
       (into #{})))

(def default-ignores #{'org.clojure/clojure})

(def default-output-file-path "bom.json")

(defn create-bom-file!
  "Given some `dependencies`, create a BOM file describing the (transitive)
  dependencies at the given `output-path`."
  [dependencies & [{:keys [maven-repositories ignores output-path]
                    :or {maven-repositories {"clojars" "https://repo.clojars.org"
                                             "maven" "https://repo1.maven.org/maven2"}
                         output-path default-output-file-path}}]]
  (let [[local-repo tree] (make-dependency-tree
                           dependencies
                           maven-repositories
                           false)
        tree (into {} tree)
        direct-dependencies (into #{} (map first dependencies))
        ignores (into default-ignores ignores)
        all-poms (collect-all-poms local-repo)
        poms (->> (dependencies->poms tree direct-dependencies all-poms ignores)
                  (mapv (fn [[direct-dependency? pom]]
                          (try
                            [direct-dependency? (-> pom slurp xml/parse-str)]
                            (catch Exception e
                              (println (str "ERROR reading " (pr-str pom) ":") (.getMessage e))))))
                  (filter some?))
        boms (->> (mapv pom->bom poms)
                  (sort-by :artifactId))]
    (spit output-path (with-out-str (json/pprint {:entries boms} :escape-slash false)))
    (println "Final package count:" (count boms))))
