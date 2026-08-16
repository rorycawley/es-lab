(ns cart.slice.add-product-item.adapter.in.http
  "Ring driving adapter for add-product-item."
  (:require [cart.adapter.in.http-response :as http-response]
            [cart.slice.add-product-item.port :as port]))

(defn handler
  ([use-case] (handler use-case {}))
  ([use-case {:keys [correlation-id-fn]}]
   (fn [request]
     (try
       (-> (port/add-product-item use-case (http-response/body-map request))
           http-response/response)
       (catch com.fasterxml.jackson.core.JsonProcessingException _
         (http-response/response
          {:outcome :invalid
           :code :invalid-json
           :field-errors [{:field "body" :code :invalid-json}]}))
       (catch Throwable throwable
         (http-response/unexpected-response throwable
                                            (or correlation-id-fn
                                                #(java.util.UUID/randomUUID))))))))
