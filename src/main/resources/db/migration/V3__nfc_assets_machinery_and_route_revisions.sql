insert into roles(code,description) values ('MANAGER','Authorized in-progress Lot and route amendments')
on conflict(code) do nothing;

create table storage_areas (
  id uuid primary key default gen_random_uuid(), station_id uuid not null references stations(id),
  code varchar(80) not null, name varchar(150) not null, active boolean not null default true,
  created_at timestamptz not null default now(), version bigint not null default 0,
  unique(station_id,code)
);
alter table storage_locations add column storage_area_id uuid references storage_areas(id);
insert into storage_areas(id,station_id,code,name)
select gen_random_uuid(),station_id,'LEGACY','Migrated Storage Area' from storage_locations group by station_id;
update storage_locations sl set storage_area_id=sa.id from storage_areas sa
where sa.station_id=sl.station_id and sa.code='LEGACY' and sl.storage_area_id is null;
alter table storage_locations alter column storage_area_id set not null;

create table machines (
  id uuid primary key default gen_random_uuid(), station_id uuid not null references stations(id),
  name varchar(150) not null, machine_type varchar(80) not null,
  status varchar(30) not null default 'AVAILABLE'
    check(status in ('AVAILABLE','IN_USE','DOWN','MAINTENANCE','INACTIVE')),
  active boolean not null default true, created_at timestamptz not null default now(), version bigint not null default 0
);
create index ix_machines_station on machines(station_id,status);

alter table nfc_tags drop constraint if exists nfc_tags_asset_type_check;
alter table nfc_tags drop constraint if exists nfc_tags_check;
alter table nfc_tags add column station_id uuid references stations(id);
alter table nfc_tags add column process_id uuid references processes(id);
alter table nfc_tags add column machine_id uuid references machines(id);
alter table nfc_tags add column storage_area_id uuid references storage_areas(id);
update nfc_tags set asset_type='STORAGE_UNIT' where asset_type='STORAGE_LOCATION';
alter table nfc_tags add constraint nfc_tags_asset_type_check
  check(asset_type in ('TROLLEY','STATION','PROCESS','MACHINE','STORAGE_AREA','STORAGE_UNIT'));
alter table nfc_tags add constraint nfc_tags_exactly_one_asset check (
  num_nonnulls(trolley_id,storage_location_id,station_id,process_id,machine_id,storage_area_id)=1 and
  (asset_type<>'TROLLEY' or trolley_id is not null) and
  (asset_type<>'STATION' or station_id is not null) and
  (asset_type<>'PROCESS' or process_id is not null) and
  (asset_type<>'MACHINE' or machine_id is not null) and
  (asset_type<>'STORAGE_AREA' or storage_area_id is not null) and
  (asset_type<>'STORAGE_UNIT' or storage_location_id is not null)
);
create unique index uq_active_nfc_station on nfc_tags(station_id) where active and station_id is not null;
create unique index uq_active_nfc_process on nfc_tags(process_id) where active and process_id is not null;
create unique index uq_active_nfc_machine on nfc_tags(machine_id) where active and machine_id is not null;
create unique index uq_active_nfc_trolley on nfc_tags(trolley_id) where active and trolley_id is not null;

create table nfc_assignment_history (
  id uuid primary key default gen_random_uuid(), nfc_tag_id uuid not null references nfc_tags(id),
  asset_type varchar(30) not null, asset_id uuid not null, assigned_by uuid references users(id),
  assigned_at timestamptz not null default now(), unassigned_by uuid references users(id),
  unassigned_at timestamptz, reason varchar(500)
);
create index ix_nfc_history_asset on nfc_assignment_history(asset_type,asset_id,assigned_at);

create table process_machine_usage (
  id uuid primary key default gen_random_uuid(), process_execution_id uuid not null references process_executions(id),
  machine_id uuid not null references machines(id), started_by uuid references users(id),
  started_at timestamptz not null default now(), completed_by uuid references users(id), completed_at timestamptz,
  replacement_for_usage_id uuid references process_machine_usage(id), reason varchar(500)
);
create unique index uq_active_machine_usage on process_machine_usage(machine_id) where completed_at is null;

drop index if exists uq_active_station_token;
alter table storage_occupancies drop column if exists token_number;
alter table storage_occupancies drop column if exists normalized_token_number;

create table style_revisions (
  id uuid primary key default gen_random_uuid(), style_id uuid not null references styles(id),
  revision_no integer not null, actor_id uuid not null references users(id), actor_role varchar(30) not null,
  reason varchar(500) not null, before_snapshot jsonb not null, after_snapshot jsonb not null,
  affected_lot_count integer not null, created_at timestamptz not null default now(),
  unique(style_id,revision_no)
);
alter table lot_route_entries drop constraint if exists lot_route_entries_lot_id_station_id_key;
create table lot_route_revisions (
  id uuid primary key default gen_random_uuid(), style_revision_id uuid not null references style_revisions(id),
  lot_id uuid not null references lots(id), revision_no integer not null,
  before_snapshot jsonb not null, after_snapshot jsonb not null, created_at timestamptz not null default now(),
  unique(lot_id,revision_no)
);
alter table lots add column original_quantity integer;
update lots set original_quantity=quantity where original_quantity is null;
alter table lots alter column original_quantity set not null;
create function nwtrack_set_original_quantity() returns trigger language plpgsql as $$
begin
  if new.original_quantity is null then new.original_quantity := new.quantity; end if;
  return new;
end $$;
create trigger trg_lots_original_quantity before insert on lots
for each row execute function nwtrack_set_original_quantity();

create table lot_quantity_changes (
  id uuid primary key default gen_random_uuid(), lot_id uuid not null references lots(id),
  old_quantity integer not null, new_quantity integer not null, actor_id uuid not null references users(id),
  reason varchar(500) not null, changed_at timestamptz not null default now()
);
