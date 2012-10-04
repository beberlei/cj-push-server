(ns cj-push-server.util)

;debugging parts of expressions
(defmacro dbg[x] `(let [x# ~x] (println "dbg:" '~x "=" x#) x#))

(def VALID-CHARS
  (map char (concat (range 48 58) ; 0-9
                    (range 66 91) ; A-Z
                    (range 97 123)))) ; a-z

(defn random-char []
  (nth VALID-CHARS (rand (count VALID-CHARS))))

(defn random-str [length]
  (apply str (take length (repeatedly random-char)))) 
