\set ON_ERROR_STOP on
-- One-time, reviewed cluster setup. Run as postgres in the postgres database.
-- Refuses collisions instead of changing another project's roles or database.
\getenv auth_password STARLIT_AUTH_DB_PASSWORD
\getenv rest_password STARLIT_REST_DB_PASSWORD
begin;
create role anon nologin noinherit;
create role authenticated nologin noinherit;
create role starlit_owner nologin;
create role starlit_auth_admin login password :'auth_password' nosuperuser nocreatedb nocreaterole noreplication;
create role starlit_authenticator login password :'rest_password' noinherit nosuperuser nocreatedb nocreaterole noreplication;
grant anon, authenticated to starlit_authenticator;
commit;
-- CREATE DATABASE must run outside a transaction.
create database starlit owner starlit_owner;
revoke all on database starlit from public;
grant connect on database starlit to starlit_auth_admin, starlit_authenticator;
\connect starlit
begin;
revoke all on schema public from public;
grant usage on schema public to anon, authenticated;
create schema auth authorization starlit_auth_admin;
revoke all on schema auth from public;
alter role starlit_auth_admin in database starlit set search_path = auth;
alter role anon in database starlit set statement_timeout = '3s';
alter role authenticated in database starlit set statement_timeout = '8s';
commit;
