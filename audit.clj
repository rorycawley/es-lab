(ns audit
  "Mechanical consistency checks across the whole repository.

  Two audits of this repo found the same failure mode: **drift**. Claims that
  were true when written and quietly stopped being true as the sequence grew —
  a forward pointer to a lab that got renumbered, a docstring saying `copied
  from lab 8, unchanged` about a file that has since changed, a prerequisite
  list written when only one lab needed Docker.

  Every one of those is mechanically checkable, and none of them was checked.
  The repository's whole discipline is *assert it in a test*; its prose was the
  one thing not asserted. This is that assertion.

  Run with `bb audit`. Exits non-zero if anything fails."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- lab-dirs []
  (->> (fs/list-dir ".")
       (filter fs/directory?)
       (map (comp str fs/file-name))
       (filter #(re-matches #"lab\d+" %))
       (sort-by #(parse-long (subs % 3)))
       vec))

(defn- lab-num [d] (parse-long (subs d 3)))

(defn- markdown-files []
  (concat ["README.md" "REFERENCE.md"]
          (map #(str % "/README.md") (lab-dirs))))

(defn- slug
  "GitHub's heading anchor: lowercase, punctuation dropped, spaces to hyphens."
  [heading]
  (-> heading str/trim str/lower-case
      (str/replace #"[`.,?'\"]" "")
      (str/replace #"[^\w\s-]" "")
      str/trim
      (str/replace #"\s" "-")))   ; each space, not each run — "a — b" has two

(defn- anchors [path]
  (into #{} (comp (keep #(second (re-matches #"#+\s+(.*)" %)))
                  (map slug))
        (str/split-lines (slurp path))))

(defn- section
  "The body of a `## Heading` section, or nil."
  [path heading]
  (let [lines (str/split-lines (slurp path))
        from  (->> lines (keep-indexed #(when (= %2 (str "## " heading)) %1)) first)]
    (when from
      (->> (drop (inc from) lines)
           (take-while #(not (str/starts-with? % "## ")))
           (str/join "\n")))))

(defn- code-body
  "A Clojure file from its first top-level `def` onward — its ns form and
  docstring dropped, so two files can be compared on what they *do*."
  [path]
  (->> (str/split-lines (slurp path))
       (drop-while #(not (re-find #"^\(def" %)))
       (str/join "\n")))

(defn- delab
  "Normalise lab numbering so labN and labM files compare equal."
  [s]
  (str/replace s #"lab\d+" "labN"))

;; ---------------------------------------------------------------------------
;; Checks. Each returns a seq of findings.
;; ---------------------------------------------------------------------------

(defn check-links
  "Every relative link resolves, and every fragment names a real heading."
  []
  (reduce
   (fn [findings f]
     (reduce
      (fn [findings [_ link]]
        (if (str/starts-with? link "http")
          findings
          (let [[path frag] (str/split link #"#" 2)
                target (if (str/blank? path)
                         f
                         (let [p (str (fs/normalize
                                       (fs/path (or (fs/parent f) ".") path)))]
                           (if (fs/directory? p) (str p "/README.md") p)))
                finding (cond
                          (not (fs/exists? target))
                          (str f ": link to " link " — no such file")

                          (and frag (not (contains? (anchors target) frag)))
                          (str f ": link to " link " — no such heading in " target)

                          :else nil)]
            (cond-> findings finding (conj finding)))))
      findings
      (re-seq #"\]\(([^)]+)\)" (slurp f))))
   []
   (markdown-files)))

(defn check-forward-pointers
  "Each lab's 'What's next' names the lab that follows it."
  []
  (let [labs (lab-dirs)
        last-n (lab-num (last labs))]
    (reduce (fn [findings d]
              (let [n (lab-num d)
                    body (section (str d "/README.md") "What's next")
                    finding (cond
                              (nil? body)
                              (str d ": no \"What's next\" section")

                              (and (< n last-n)
                                   (not (re-find
                                         (re-pattern (str "lab" (inc n) "\\b"))
                                         body)))
                              (str d ": \"What's next\" does not point at lab" (inc n))

                              :else nil)]
                (cond-> findings finding (conj finding))))
            []
            labs)))

(defn check-copied-claims
  "A namespace claiming to be another lab's file, unchanged, must still be it.

  This is the check that would have caught three of the last audit's five
  findings: copying a lab forward carries its *claims* along with its code."
  []
  (reduce
   (fn [findings d]
     (reduce
      (fn [findings f]
        (let [head (str/join "\n" (take 12 (str/split-lines (slurp f))))
              m (re-find #"(?i)(?:unchanged|copied)[^.]{0,30}?lab ?(\d+)" head)]
          (if-not m
            findings
            (let [source-lab (str "lab" (second m))
                  basename   (str (fs/file-name f))
                  ;; A glob, not a constructed path: from lab 25 the labs nest
                  ;; their sources under a capability directory.
                  candidates (mapv str (fs/glob source-lab (str "src/**/" basename)))
                  finding (cond
                            (empty? candidates)
                            (str f ": claims to come from " source-lab
                                 ", which has no " basename)

                            (next candidates)
                            (str f ": claims to come from " source-lab ", which has "
                                 (count candidates) " files named " basename)

                            (not= (delab (code-body (first candidates)))
                                  (delab (code-body f)))
                            (str f ": claims to be " source-lab "'s " basename
                                 " unchanged — the code differs")

                            :else nil)]
              (cond-> findings finding (conj finding))))))
      findings
      (mapv str (fs/glob d "src/**/*.clj"))))
   []
   (lab-dirs)))

(defn check-lab-index
  "The root README's table and summaries cover exactly the labs that exist."
  []
  (let [readme (slurp "README.md")
        tabled (set (map second (re-seq #"\|\s*\[(\d+)\]\(lab\d+\)" readme)))
        summarised (->> (re-seq #"(?m)^\*\*Labs? (\d+)(?:[–-](\d+))?" readme)
                        (mapcat (fn [[_ a b]]
                                  (if b
                                    (map str (range (parse-long a) (inc (parse-long b))))
                                    [a])))
                        set)]
    (-> (reduce (fn [findings d]
                  (let [n (str (lab-num d))]
                    (cond-> findings
                      (not (tabled n))
                      (conj (str "README.md: " d " is missing from the labs table"))

                      (not (summarised n))
                      (conj (str "README.md: " d " has no summary paragraph")))))
                []
                (lab-dirs))
        (as-> findings
              (reduce (fn [findings n]
                        (cond-> findings
                          (not (fs/exists? (str "lab" n)))
                          (conj (str "README.md: table lists lab" n ", which does not exist"))))
                      findings
                      tabled)))))

(defn check-docker-declared
  "Labs needing a container are named in the root README's prerequisites."
  []
  (let [readme (slurp "README.md")
        needs  (reduce (fn [labs d]
                         (cond-> labs
                           (str/includes? (slurp (str d "/deps.edn")) "testcontainers")
                           (conj d)))
                       []
                       (lab-dirs))]
    (reduce (fn [findings d]
              (cond-> findings
                (not (re-find
                      (re-pattern (str "(?i)lab ?" (lab-num d) "\\]?\\(" d "\\)"))
                      readme))
                (conj (str "README.md: " d
                           " needs Docker but is not named in the prerequisites"))))
            []
            needs)))

(defn check-payload-discipline
  "`payload` is lab 1's word for a message in transit, never for an event's
  own data. Both audits found this slipping."
  []
  (let [licensed #"(?i)message|integration|:payload|lab ?3|lab ?12|contract|wire|transit|delivery|the right word|reappears|inside the payload|envelope|not a blob"]
    (reduce
     (fn [findings f]
       (reduce (fn [findings [i line]]
                 (cond-> findings
                   (and (str/includes? (str/lower-case line) "payload")
                        (not (re-find licensed line)))
                   (conj (str f ":" (inc i)
                              ": \"payload\" used outside a transport context — "
                              "lab 1 reserves it for messages in transit"))))
               findings
               (map-indexed vector (str/split-lines (slurp f)))))
     []
     (markdown-files))))

;; ---------------------------------------------------------------------------

(def checks
  [["links and anchors"      check-links]
   ["forward pointers"       check-forward-pointers]
   ["copied-file claims"     check-copied-claims]
   ["lab index"              check-lab-index]
   ["docker declared"        check-docker-declared]
   ["payload discipline"     check-payload-discipline]])

(defn -main [& _]
  (let [results (reduce (fn [results [name f]]
                          (conj results [name (vec (remove nil? (f)))]))
                        []
                        checks)
        total   (reduce + (map (comp count second) results))]
    (doseq [[name findings] results]
      (println (format "%-22s %s" name (if (seq findings)
                                         (str (count findings) " ✗")
                                         "ok")))
      (doseq [finding findings]
        (println "   " finding)))
    (println)
    (if (zero? total)
      (println "No inconsistencies found.")
      (println total "finding(s)."))
    (System/exit (if (zero? total) 0 1))))
