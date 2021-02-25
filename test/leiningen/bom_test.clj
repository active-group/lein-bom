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

(deftest dependency->path+pom-test
  (is (= ["base/clj-commons/pomegranate/1.2.0" "pomegranate-1.2.0.pom"]
         (bom/dependency->path+pom "base" ['clj-commons/pomegranate "1.2.0"]))))

(deftest dependencies->directory-test
  (testing "with no transitive dependencies"
    (is (= [["base/clojure-complete/0.2.5"
             "clojure-complete-0.2.5.pom"]]
           (bom/dependencies->paths+poms
            "base"
            {['clojure-complete "0.2.5" :exclusions [['org.clojure/clojure]]] nil}))))
  (testing "with one transitive dependency"
    (is (= [["base/clojure-complete/0.2.5"
             "clojure-complete-0.2.5.pom"]
            ["base/org/codehaus/plexus/plexus-interpolation/1.25"
             "plexus-interpolation-1.25.pom"]]
           (bom/dependencies->paths+poms
            "base"
            {['clojure-complete "0.2.5" :exclusions [['org.clojure/clojure]]]
             {['org.codehaus.plexus/plexus-interpolation "1.25"] nil}}))))
  (testing "a complex case"
    (is (= #{["base/clj-commons/pomegranate/1.2.0"
              "pomegranate-1.2.0.pom"]
             ["base/org/apache/httpcomponents/httpclient/4.5.8"
              "httpclient-4.5.8.pom"]
             ["base/commons-codec/1.11"
              "commons-codec-1.11.pom"]
             ["base/commons-logging/1.2"
              "commons-logging-1.2.pom"]
             ["base/org/apache/httpcomponents/httpcore/4.4.11"
              "httpcore-4.4.11.pom"]
             ["base/org/apache/maven/maven-resolver-provider/3.6.1"
              "maven-resolver-provider-3.6.1.pom"]
             ["base/javax/inject/1"
              ;; interesting test case: the package is actually named java.inject ...
              ;; so this is correct :)
              "javax.inject-1.pom"]
             ["base/org/apache/maven/maven-model-builder/3.6.1"
              "maven-model-builder-3.6.1.pom"]}
           (into #{} (bom/dependencies->paths+poms "base" tree))))))
