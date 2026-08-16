CREATE INDEX service_requests_search_vector_idx
  ON service_requests USING GIN (search_vector);
