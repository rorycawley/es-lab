CREATE ROLE cart_migrator LOGIN PASSWORD 'cart_migrator';
CREATE ROLE cart_app LOGIN PASSWORD 'cart_app';

CREATE DATABASE event_store WITH OWNER cart_migrator ENCODING 'UTF8' TEMPLATE template0;
GRANT CONNECT ON DATABASE event_store TO cart_app;
