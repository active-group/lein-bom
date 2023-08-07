(ns active.bom-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [active.bom :as bom]))

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
         (io/delete-file bom/default-output-file-path true))))

(use-fixtures :each clean-up-golden-test-file)
(deftest test-golden
  (testing "produces expected output"
    (is (= (slurp "test-resources/expected_bom.json")
           (do
             (bom/create-bom-file! [['clj-commons/pomegranate "1.2.0"]
                                    ['org.clojure/data.xml "0.0.8"]])
             (slurp bom/default-output-file-path))))))
