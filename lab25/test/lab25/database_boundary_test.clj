(ns lab25.database-boundary-test
  "A Postgres integration test for module data ownership, not a business rule.

  The module boundary reaches database permissions, not just package names."
  (:require [clojure.test :refer [deftest is testing]]
            [lab25.fixture :as fixture]
            [next.jdbc :as jdbc]))

(deftest module-database-identities-cannot-cross-the-boundary-test
  (fixture/with-system
    (fn [{:keys [catalog ordering]}]
      (testing "Catalog cannot read Ordering's tables"
        (is (thrown? java.sql.SQLException
                     (jdbc/execute-one! (:datasource catalog)
                                        ["SELECT count(*) FROM ordering.orders"]))))
      (testing "Ordering cannot read Catalog's tables"
        (is (thrown? java.sql.SQLException
                     (jdbc/execute-one! (:datasource ordering)
                                        ["SELECT count(*) FROM catalog.product"])))))))
