(ns cart.slice.add-product-item.port)

(defprotocol AddProductItem
  (add-product-item [handler request]))
