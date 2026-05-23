(ns es-lab.task-persistence.audit.port)

(defprotocol AuditPort
  (record! [port event]))
