CREATE USER cart_migrator WITH PASSWORD 'cart_migrator';
CREATE USER cart_app WITH PASSWORD 'cart_app';
CREATE DATABASE cart OWNER cart_migrator;
\connect cart
GRANT CONNECT ON DATABASE cart TO cart_app;
GRANT USAGE ON SCHEMA public TO cart_app;
