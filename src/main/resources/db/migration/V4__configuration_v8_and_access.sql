-- Resolve old multi-role accounts before enforcing one fixed role per user.
create temporary table migrated_roles as
select ur.user_id, (array_agg(case r.code
 when 'SUPER_ADMIN' then 'NW' when 'ADMIN' then 'ADMIN_IT'
 when 'MANAGER' then 'PRODUCTION_MANAGER' when 'OPERATOR' then 'PROCESS_MANAGER'
 when 'SUPERVISOR' then 'SUPERVISOR' else null end
 order by case r.code when 'SUPER_ADMIN' then 1 when 'ADMIN' then 2 when 'MANAGER' then 3
 when 'OPERATOR' then 4 when 'SUPERVISOR' then 5 else 6 end))[1] code
from user_roles ur join roles r on r.id=ur.role_id group by ur.user_id;
delete from user_roles;
delete from roles;
insert into roles(code,description) values
 ('NW','NW'),('TOP_MANAGER','Top Manager'),('PRODUCTION_MANAGER','Production Manager'),
 ('PROCESS_MANAGER','Process Manager'),('SUPERVISOR','Supervisor'),('ADMIN_IT','Admin (IT)'),('STATION_0','Station 0');
insert into user_roles select m.user_id,r.id from migrated_roles m join roles r on r.code=m.code;
update users set enabled=false where id in (select user_id from migrated_roles where code is null);
create unique index uq_user_one_role on user_roles(user_id);
alter table roles add constraint fixed_role_codes check(code in ('NW','TOP_MANAGER','PRODUCTION_MANAGER','PROCESS_MANAGER','SUPERVISOR','ADMIN_IT','STATION_0'));
drop table migrated_roles;
alter table roles add column pc_access boolean not null default true;
alter table roles add column pad_access boolean not null default true;
update roles set pad_access=false where code='TOP_MANAGER';
update roles set pc_access=false where code='SUPERVISOR';
create table role_permissions (
 role_id uuid not null references roles(id), permission varchar(50) not null,
 primary key(role_id,permission)
);
insert into role_permissions
select r.id,p.permission from roles r cross join (values
 ('CONFIGURE'),('USERS'),('ROLES'),('STYLES'),('OPERATE'),('QC'),('REPORTS'),('OVERRIDE'),('TRANSPORT'),('READ')) p(permission)
where r.code in ('NW','ADMIN_IT')
 or (r.code='PRODUCTION_MANAGER' and p.permission in ('STYLES','OPERATE','QC','REPORTS','OVERRIDE','TRANSPORT','READ'))
 or (r.code='TOP_MANAGER' and p.permission in ('REPORTS','READ'))
 or (r.code='PROCESS_MANAGER' and p.permission in ('OPERATE','QC','REPORTS','TRANSPORT','READ'))
 or (r.code='SUPERVISOR' and p.permission in ('OPERATE','QC','OVERRIDE','READ'))
 or (r.code='STATION_0' and p.permission in ('STYLES','OPERATE','QC','TRANSPORT','READ'));
alter table user_station_assignments drop constraint user_station_assignments_pkey;
alter table user_station_assignments add primary key(user_id,station_id);
create table user_process_assignments (
 user_id uuid not null references users(id), process_id uuid not null references processes(id),
 primary key(user_id,process_id)
);
-- Preserve access to existing assigned stations during migration.
insert into user_process_assignments select usa.user_id,p.id from user_station_assignments usa join processes p on p.station_id=usa.station_id;
alter table processes add column description varchar(2000);
alter table processes add column nfc_mode varchar(20) not null default 'PROCESS' check(nfc_mode in ('PROCESS','DEVICES'));
alter table processes add column quality boolean not null default false;
update processes set quality=true where station_id in (select id from stations where is_qc);
alter table machines add column process_id uuid references processes(id);
alter table machines add column description varchar(2000);
-- Assign legacy machines where station mapping is unambiguous; otherwise disable until assigned.
update machines m set process_id=(select min(p.id::text)::uuid from processes p where p.station_id=m.station_id)
where (select count(*) from processes p where p.station_id=m.station_id)=1;
update machines set active=false,status='INACTIVE' where process_id is null;
-- Legacy processes used both tag types. Retain their process tags, disable devices until explicitly reconfigured.
update machines set active=false,status='INACTIVE';
alter table storage_areas add column description varchar(2000);
alter table nfc_tags add column name varchar(150);
do $$ begin
 if exists(select 1 from nfc_tags group by upper(uid) having count(*)>1) then
  raise exception 'Resolve NFC UID case duplicates before upgrading';
 end if;
end $$;
update nfc_tags set uid=upper(uid);
create unique index uq_nfc_uid_ci on nfc_tags(upper(uid));
update nfc_tags set name=uid;
alter table nfc_tags alter column name set not null;
alter table nfc_tags alter column asset_type drop not null;
alter table nfc_tags add column enabled boolean not null default true;
create unique index uq_enabled_nfc_name_ci on nfc_tags(lower(name)) where enabled;
alter table nfc_tags drop constraint nfc_tags_exactly_one_asset;
alter table nfc_tags add constraint nfc_tags_exactly_one_asset check (
 (asset_type is null and num_nonnulls(trolley_id,storage_location_id,station_id,process_id,machine_id,storage_area_id)=0 and not active)
 or (asset_type is not null and num_nonnulls(trolley_id,storage_location_id,station_id,process_id,machine_id,storage_area_id)=1 and
 (asset_type<>'TROLLEY' or trolley_id is not null) and (asset_type<>'STATION' or station_id is not null) and
 (asset_type<>'PROCESS' or process_id is not null) and (asset_type<>'MACHINE' or machine_id is not null) and
 (asset_type<>'STORAGE_AREA' or storage_area_id is not null) and (asset_type<>'STORAGE_UNIT' or storage_location_id is not null)));
alter table refresh_tokens add column client_type varchar(3) not null default 'PC' check(client_type in ('PC','PAD'));
create unique index uq_station_reference_ci on stations(lower(code));
alter table qc_inspections add constraint qc_nonnegative check(good_quantity>=0 and rejected_quantity>=0 and inspected_quantity>0);

-- Keep alternative administration endpoints from violating NFC mode or device ownership.
create function nwtrack_device_owner() returns trigger language plpgsql as $$
begin
 if new.active and (new.process_id is null or not exists(select 1 from processes p where p.id=new.process_id and p.station_id=new.station_id and p.nfc_mode='DEVICES' and p.active)) then
  raise exception 'Active devices require an active owning process in DEVICES mode at the same station';
 end if;
 if tg_op='UPDATE' and old.process_id is not null and new.process_id is distinct from old.process_id then
  raise exception 'A device cannot change its owning process';
 end if;
 if tg_op='UPDATE' and exists(select 1 from process_machine_usage where machine_id=old.id and completed_at is null) and
    (new.process_id is distinct from old.process_id or new.station_id<>old.station_id or (old.active and not new.active)) then
  raise exception 'An in-use device cannot be moved or disabled';
 end if;
 return new;
end $$;
create trigger trg_device_owner before insert or update on machines for each row execute function nwtrack_device_owner();
create function nwtrack_nfc_mode() returns trigger language plpgsql as $$
begin
 if new.active and not new.enabled then raise exception 'Disabled tag cannot be assigned'; end if;
 if new.active and new.asset_type='PROCESS' and not exists(select 1 from processes where id=new.process_id and nfc_mode='PROCESS' and active) then
  raise exception 'Process NFC requires PROCESS mode';
 end if;
 if new.active and new.asset_type='MACHINE' and not exists(select 1 from machines m join processes p on p.id=m.process_id where m.id=new.machine_id and p.nfc_mode='DEVICES' and m.active and p.active) then
  raise exception 'Device NFC requires DEVICES mode';
 end if;
 return new;
end $$;
create trigger trg_nfc_mode before insert or update on nfc_tags for each row execute function nwtrack_nfc_mode();
