(defproject de.active-group/lein-bom "0.2.1"
  :description "Generate a BOM file from a project.clj."
  :url "https://github.com/active-group/lein-bom"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[clj-commons/pomegranate "1.2.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.json "1.0.0"]]
  :eval-in-leiningen true)
