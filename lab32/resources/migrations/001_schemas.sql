-- Three schemas, three logins, and one database.
--
-- Lab 25 organised a monolith by capability and lab 29 gave each module its own
-- Postgres role, so that an accidental cross-module query is refused by the
-- database rather than caught in review. That rule is load bearing here for a
-- new reason: this lab's entire claim is that two modules can exchange events
-- through a shared database *without* sharing state. If Accounts could read
-- Compliance's tables, the outbox would be theatre.
--
-- `messaging` is not a module. It is the transport, and it gets an identity of
-- its own because the dispatcher is the one thing in the system that must
-- legitimately touch two modules at once -- claiming from the outbox and
-- inserting into an inbox inside a single transaction (§6.4). Giving that
-- power to Accounts or to Compliance would hand a module the ability to write
-- into another one's inbox, which is precisely the boundary being defended.

CREATE ROLE accounts_module   LOGIN PASSWORD 'accounts-pass';
CREATE ROLE compliance_module LOGIN PASSWORD 'compliance-pass';
CREATE ROLE messaging_module  LOGIN PASSWORD 'messaging-pass';

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA accounts   AUTHORIZATION accounts_module;
CREATE SCHEMA compliance AUTHORIZATION compliance_module;
CREATE SCHEMA messaging  AUTHORIZATION messaging_module;
