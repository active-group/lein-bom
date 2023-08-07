(ns leiningen.bom-test
  (:require [clojure.test :refer :all]
            [leiningen.bom :as bom]))

;; The test tree (deps for this project)
(def tree {['clj-commons/pomegranate "1.2.0"]
           {['org.apache.httpcomponents/httpclient "4.5.8"]
            {['commons-codec "1.11"] nil
             ['commons-logging "1.2"] nil},
            ['org.apache.httpcomponents/httpcore "4.4.11"] nil,
            ['org.apache.maven/maven-resolver-provider "3.6.1"]
            {['javax.inject "1"] nil,
             ['org.apache.maven/maven-model-builder "3.6.1"] nil}}})

(deftest test-dep->pom-name
  (testing "just name and version"
    (is (= "pomegranate-1.2.0.pom"
           (bom/dep->pom-name ["pomegranate" "1.2.0"]))))
  (testing "ignores superfluous args"
    (is (= "pomegranate-1.2.0.pom"
           (bom/dep->pom-name ["pomegranate" "1.2.0" "MORE" "ARGS" 12345])))))
