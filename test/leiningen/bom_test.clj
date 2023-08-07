(ns leiningen.bom-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io]
            [leiningen.bom :as bom]))

(deftest test-dep->pom-name
  (testing "just name and version"
    (is (= "pomegranate-1.2.0.pom"
           (bom/dep->pom-name ["pomegranate" "1.2.0"]))))
  (testing "ignores superfluous args"
    (is (= "pomegranate-1.2.0.pom"
           (bom/dep->pom-name ["pomegranate" "1.2.0" "MORE" "ARGS" 12345])))))

(defn clean-up-golden-test-file [f]
  (try (f)
       (finally
         (io/delete-file bom/output-file-name true))))

(use-fixtures :each clean-up-golden-test-file)
(deftest test-golden
  (testing "produces expected output"
    (is (= (slurp "test-resources/expected_bom.json")
           (let [deps [['clj-commons/pomegranate "1.2.0"]
                       ['org.clojure/data.xml "0.0.8"]]
                 [local-repo tree]
                 (bom/make-dependency-tree deps
                                           {"clojars" "https://repo.clojars.org"
                                            "maven" "https://repo1.maven.org/maven2"}
                                           false)
                 direct-dependencies (into #{} (map first deps))
                 tree (into {} tree)
                 all-poms (bom/collect-all-poms local-repo)
                 poms (->> (bom/dependencies->poms tree direct-dependencies all-poms bom/ignore-default)
                           (mapv (fn [[direct-dependency? pom]]
                                   (try
                                     [direct-dependency? (-> pom slurp xml/parse-str)]
                                     (catch Exception e
                                       (println (str "ERROR reading " (pr-str pom) ":") (.getMessage e))))))
                           (filter some?))
                 boms (->> (mapv bom/pom->bom poms)
                           (sort-by :artifactId))]
             (spit bom/output-file-name (with-out-str (json/pprint {:entries boms} :escape-slash false)))
             (slurp bom/output-file-name))))))
