(ns hiredman.deps)


(defn ffile [file]
  (if (string? file)
    (java.io.File. file)
    file))

(defn read-ns [file]
  (with-open [f (-> file ffile java.io.FileReader.
                java.io.PushbackReader.)]
    (binding [*in* f]
      (let [x (read)]
        (if (= (first x) 'ns)
          x
          nil)))))


(defn get-required [ns-form]
  (set
  (mapcat
    (fn [x]
      (if (list? x)
        (map #(symbol (str (name (first x)) "." (name %))) (rest x))
        (list x)))
    (map #(if (vector? %) (first %) %) (mapcat rest (filter #(and (or (vector? %) (list? %)) (= :require (first %))) ns-form))))))


(defn get-used [ns-form]
  (-> ns-form
    ((partial filter #(and (coll? %) (= :use (first %)))))
    ((partial map rest))
    ((partial apply concat))
    ((partial mapcat
              (fn [x]
                (if (coll? x)
                  (map #(symbol (str (name (first x)) "." (name %))) (rest x))
                  [x]))))))



(defn get-import [ns-form]
  (-> ns-form
    ((partial filter #(and (coll? %) (= :import (first %)))))
    ((partial map rest))
    ((partial apply concat))
    ((partial mapcat
              (fn [x]
                (if (coll? x)
                  (map #(symbol (str (name (first x)) "." (name %))) (rest x))
                  [x]))))))

(defn parse [ns-form]
  {:name (second ns-form)
   :clojure-depends (concat (get-required ns-form)
                            (get-used ns-form))
   :java-depends (get-import ns-form)})

(defn all-clojure-files [root]
  (let [root (ffile root)]
    (if (.isDirectory root)
      (mapcat all-clojure-files (.listFiles root
                                            (proxy [java.io.FilenameFilter] []
                                              (accept [dir name]
                                                      (or (.endsWith name ".clj")
                                                          (.isDirectory (java.io.File. dir name)))))))
      [root])))



(defn parse-directory [dir]
  (reduce #(assoc % (:name %2) (dissoc %2 :name)) {} (map (comp parse read-ns) (all-clojure-files dir))))

(defn restructure [files]
(reduce
  (fn [map- dep]
    (-> map- (update-in [:java] #(into % (:java-depends (second dep))))
      (update-in [:clojure] #(conj (into % (:clojure-depends (second dep)))
                                   (first dep)))
      (update-in [:edges]
                 #(into %
                        (map (partial vector (first dep))
                             (concat (:java-depends (second dep))
                                     (:clojure-depends (second dep))))))))
  {:java #{} :clojure #{} :edges #{}}
  files))

(defn dot [x]
  (format "digraph simple_hierarchy {\n graph [rankdir = \"LR\"];\n %s subgraph cluster_clojure {\nlabel=\"clojure\";\ncolor=blue;\n%s} subgraph cluster_java {\nlabel=\"java\";\ncolor=red;\n%s}}" (:edges x) (:clojure x) (:java x)))
  
(defn safe-name [string]
  (str (.replaceAll (str string) "(\\.|-|\\$)" "_")))

(defn safe-name-and-label [structure tag]
  (update-in structure [tag]
             #(reduce str (map
                (fn [x]
                  (str (safe-name x) "[label=\"" x "\"];\n")) %))))

(defn edges [structure]
  (update-in structure [:edges]
             (fn [x]
               (reduce str (map #(format "%s->%s;\n" (safe-name (first %)) (safe-name (second %))) x)))))


(-> (first *command-line-args*) parse-directory
  ((partial remove (comp nil? first)))
  restructure
  (safe-name-and-label :java)
  (safe-name-and-label :clojure)
  edges
  dot
  ((fn [out]
     (binding [*out* (-> (second *command-line-args*) java.io.File. java.io.FileWriter.)]
       (println out)))))

