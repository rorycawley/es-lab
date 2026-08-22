(ns lab27.database-boundary-test
  "A Postgres integration test for module data ownership, not a business rule.

  The module boundary reaches database permissions, not just package names."
  (:require [clojure.test :refer [deftest is testing]]
            [lab27.fixture :as fixture]
            [lab27.postgres :as postgres]
            [next.jdbc :as jdbc]))

(deftest module-database-identities-cannot-cross-the-boundary-test
  (fixture/with-system
    (fn [_]
      (let [{catalog-config :catalog ordering-config :ordering} (postgres/config)
            catalog-ds  (jdbc/get-datasource catalog-config)
            ordering-ds (jdbc/get-datasource ordering-config)]
        (testing "Catalog cannot read or write Ordering's tables"
          (is (thrown? java.sql.SQLException
                       (jdbc/execute-one! catalog-ds
                                          ["SELECT count(*) FROM ordering.orders"])))
          (is (thrown? java.sql.SQLException
                       (jdbc/execute-one! catalog-ds
                                          ["DELETE FROM ordering.orders"]))))
        (testing "Ordering cannot read or write Catalog's tables"
          (is (thrown? java.sql.SQLException
                       (jdbc/execute-one! ordering-ds
                                          ["SELECT count(*) FROM catalog.product"])))
          (is (thrown? java.sql.SQLException
                       (jdbc/execute-one! ordering-ds
                                          ["DELETE FROM catalog.product"]))))))))
