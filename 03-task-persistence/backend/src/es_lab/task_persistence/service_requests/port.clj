(ns es-lab.task-persistence.service-requests.port)

(defprotocol ServiceRequestPort
  (save!    [port request])
  (list-all [port]))
