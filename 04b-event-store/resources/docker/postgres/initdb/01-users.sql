CREATE ROLE cart_migrator LOGIN PASSWORD 'cart_migrator';
CREATE ROLE cart_app LOGIN PASSWORD 'cart_app';

CREATE DATABASE event_store OWNER cart_migrator;
GRANT CONNECT ON DATABASE event_store TO cart_app;
