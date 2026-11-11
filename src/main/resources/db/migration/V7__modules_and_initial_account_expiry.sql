insert into app_modules(key,name,built_in) values
 ('trolleys','Trolley Configuration',true), ('lots','Lot Management',true);
insert into role_modules(role_id,module_key)
select r.id,m.key from roles r cross join app_modules m where
 (m.key='trolleys' and r.code in ('ADMIN_IT','NW','PRODUCTION_MANAGER'))
 or (m.key='lots' and r.code in ('ADMIN_IT','NW','PRODUCTION_MANAGER','STYLE_CONFIGURATOR'));

-- No backfill: existing users and the bootstrap administrator never expire.
alter table users add column initial_password_expires_at timestamptz;
create index ix_users_initial_password_expiry on users(initial_password_expires_at)
 where initial_password_expires_at is not null;
