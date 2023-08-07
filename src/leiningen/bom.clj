(ns leiningen.bom
  (:require [active.bom :as bom]))

(defn bom
  [project & args]
  ;; Args contains symbols we'd like to ignore
  (bom/create-bom-file! (:dependencies project)
                        {:maven-repositories (:repositories project)
                         :ignores (mapv symbol args)}))
