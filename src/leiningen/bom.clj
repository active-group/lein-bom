(ns leiningen.bom
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

(def repositories {"central" "https://repo1.maven.org/maven2/"
                   "clojars" "https://clojars.org/repo"})

(defn- make-dependency-tree
  "Fetch all dependencies for the project and put them into a new m2 repository.
  Returns the dependency tree."
  [project]
  ;; Source: https://github.com/joodie/lein-deps-tree/blob/master/src/leiningen/deps_tree.clj
  (let [local-repo (mk-tmp-dir!)]
    [local-repo (aether/dependency-hierarchy
                 (:dependencies project)
                 (aether/resolve-dependencies
                  :local-repo local-repo
                  :offline? (:offline project)
                  :repositories repositories
                  :coordinates (:dependencies project)
                  :transfer-listener :stdout))]))

(defn coordinate->package-name
  [coordinate]
  (last (string/split coordinate #"\/")))

(defn coordinate-without-group-id?
  [coordinate]
  (= 1 (count (string/split (pr-str coordinate) #"\.|\/"))))

(defn path+pom->pom
  [[path pom]]
  (let [pom-path (string/join "/" [path pom])]
    (try (->  pom-path slurp xml/parse-str)
         (catch Exception _
           nil))))

(defn pom-location-alternatives
  [coordinate version]
  (let [with-group?            (coordinate-without-group-id? coordinate)
        [group-id artifact-id] (string/split (pr-str coordinate) #"\/")
        artifact-id            (or artifact-id group-id)
        group-id               (string/replace group-id #"\." "/")
        artifact-id-2          (string/replace artifact-id #"\." "/")
        suffix                 (str artifact-id "-" version ".pom")]
    [(string/join "/" [group-id artifact-id version suffix])
     (string/join "/" [group-id artifact-id-2 version suffix])]))

(defn dependency->path+pom
  [base-dir [coordinate version & more]]
  ;; Unfortunately, some packages don't followg the convention of separating subpackages into directories.
  ;; So we check two different options:
  ;; 1. Check if the conventional path is present
  ;; 2. If not, try the package name without separating it with /.
  (let [[pom-1 pom-2] (pom-location-alternatives coordinate version)
        ;; base-path     (string/join "/" (concat [base-dir] (when (coordinate-without-group-id? coordinate)
        ;;                                                     [(pr-str coordinate)])
        ;;                                        (string/split (pr-str coordinate) #"\.|\/") [version]))
        ;; pom-file-name (str (coordinate->package-name (str coordinate)) "-" version ".pom")
        ]
    ;; try the canoncial option
    (if-let [pom (path+pom->pom [base-dir pom-1 ;pom-file-name
                                 ])]
      pom
      ;; There is no file at the canonical path.
      ;; Example: This is the case for org.clojure/clojure.data.json.
      ;; One would assume the pom can be found at .../org/clojure/data/json/1.0.0/data.json-1.0.0.pom
      ;; However, the file is actually found at   .../org/clojure/data.json/1.0.0/data.json-1.0.0.pom
      (do
        (println "Cannot find" (string/join "/" [base-dir pom-1]) ". Trying alternative path.")
        (if-let [pom (path+pom->pom [base-dir pom-2])]
          pom
          (println "Cannot find" (string/join "/" [base-dir pom-2]) "at alternative path either. Skipping."))))))

(defn dependencies->paths+poms
  [base-dir deps]
  (reduce (fn [acc [top-level transitive]]
            (concat acc
                    [(dependency->path+pom base-dir top-level)]
                    (dependencies->paths+poms base-dir transitive)))
          [] deps))

;;   What do we want from our dependencies?
;; - group-id
;; - artifact-id
;; - artifact-version
;; - licenses
;; - sources (a link to the source code will suffice)
(s/def ::string (s/and string? not-empty))
(s/def ::artifact-id ::string)
(s/def ::artifact-version ::string)

(s/def ::name ::string)
(s/def ::url ::string)

(s/def ::license (s/keys :req-un [::name ::url]))
(s/def ::licenses (s/coll-of ::license))
(s/def ::bom (s/keys :req-un [::artifact-id ::artifact-version ::licenses ::sources]
                     :opt-in [::group-id]))

(defn licenses-entry?
  [xml-element]
  (= :licenses (:tag xml-element)))

(defn get-with-tag [tag content]
  (->> content
       (filter (comp (partial = tag) :tag))
       (first)
       (:content)))

(defn find-version [content]
  (if-let [version (first (get-with-tag :version content))]
    version
    (->> content
         (get-with-tag :parent)
         (get-with-tag :version)
         (first))))

;; https://github.com/package-url/purl-spec
;; Schema: scheme:type/namespace/name@version?qualifiers#subpath

;; this is the URL scheme with the constant value of "pkg".
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
  (let [purl-scheme    "pkg"   ; constant according to spec
        purl-type      "maven" ; constant in our specific case
        purl-namespace group-id
        purl-name      artifact-id
        purl-version   version
        purl-qualifiers {}
        purl-subpath nil]
    (str purl-scheme ":" purl-type "/" purl-namespace "/" purl-name "@" purl-version)))

(defn pom->bom
  [pom]
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
          version               (first (get-tag-value-content :version content))]
      {:origin           "external"
       :directDependency true
       :usageType        "COMPONENT_DYNAMIC_LIBRARY"
       :artifactId       artifact-id
       ;; NOTE Some package do not specify a group-id. Maven convention tells us
       ;;      to use the artifact-id in these cases.
       :namespace        group-id
       :purl             (make-purl group-id artifact-id version)
       :sources          {:combined {:absolutePath project-url}} ; TODO
       :version          version
       :licenses         {:main [{:name (first (get-tag-value-content :name license))
                                  :url  (first (get-tag-value-content :url license))}]}
       :repoUrl          project-url
       :description      (first (get-tag-value-content :description content))})))

(defn bom
  [project & _]
  (let [[local-repo tree] (make-dependency-tree project)
        tree              (into {} tree)
        paths+poms        (dependencies->paths+poms local-repo tree)]
    (spit "bom.json" (with-out-str (json/pprint
                                    {:entries (mapv pom->bom paths+poms)}
                                    :escape-slash false)))))
