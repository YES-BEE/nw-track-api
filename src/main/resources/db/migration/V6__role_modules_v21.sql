-- Module grants are independent of legacy PAD action permissions.
alter table roles drop constraint fixed_role_codes;
alter table roles add column name varchar(80);
alter table roles add column built_in boolean not null default true;
alter table roles add column active boolean not null default true;
update roles set code='STYLE_CONFIGURATOR',description='Style Configurator',pc_access=true,pad_access=false where code='STATION_0';
update roles set name=case code when 'ADMIN_IT' then 'IT Admin' else description end;
alter table roles alter column name set not null;
create unique index uq_role_name_ci on roles(lower(name));
-- Match the desktop personas from v21; retain other existing PAD permissions.
update roles set pc_access=true where code in ('SUPERVISOR','PROCESS_MANAGER');
delete from role_permissions where role_id=(select id from roles where code='STYLE_CONFIGURATOR');
insert into role_permissions select id,'READ' from roles where code='STYLE_CONFIGURATOR';
insert into role_permissions select id,'CONFIGURE' from roles where code='PRODUCTION_MANAGER' on conflict do nothing;
create table app_modules (
 key varchar(80) primary key, name varchar(80) not null,
 built_in boolean not null default false, active boolean not null default true
);
create unique index uq_module_name_ci on app_modules(lower(name));
insert into app_modules(key,name,built_in) values
 ('dashboard','Dashboard',true),('reports','Reports/Analysis',true),
 ('process','Process Configurations',true),('storage','Storage Configurations',true),
 ('tags','Tag Management',true),('styles','Style Management',true),
 ('users','User Management',true),('roles','Role Management',true),('newstyle','New Style',true);
create table role_modules (
 role_id uuid not null references roles(id), module_key varchar(80) not null references app_modules(key),
 primary key(role_id,module_key)
);
insert into role_modules
select r.id,m.key from roles r cross join app_modules m where
 (r.code in ('NW','ADMIN_IT') and m.key<>'newstyle')
 or (r.code in ('TOP_MANAGER','SUPERVISOR') and m.key in ('dashboard','reports'))
 or (r.code='PRODUCTION_MANAGER' and m.key in ('dashboard','reports','process','storage','tags','styles'))
 or (r.code='STYLE_CONFIGURATOR' and m.key='newstyle');
-- Old Station 0 JWTs no longer match the renamed role. Revoke refresh sessions too.
update refresh_tokens set revoked_at=now() where revoked_at is null
 and user_id in(select ur.user_id from user_roles ur join roles r on r.id=ur.role_id where r.code='STYLE_CONFIGURATOR');
