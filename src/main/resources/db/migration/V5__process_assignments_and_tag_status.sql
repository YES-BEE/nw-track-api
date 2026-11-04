-- Process assignments are now the only source of user station scope.
alter table user_process_assignments add column active_on_shift boolean not null default false;
update user_process_assignments upa set active_on_shift=coalesce((
 select usa.active_on_shift from user_station_assignments usa
 join processes p on p.station_id=usa.station_id
 where usa.user_id=upa.user_id and p.id=upa.process_id
),false);
alter table user_process_assignments add column assigned_at timestamptz not null default now();
drop table user_station_assignments;

-- Keep lifecycle independent of assignment. Additional statuses can be introduced later.
create table nfc_tag_statuses (
 code varchar(30) primary key,
 description varchar(150) not null,
 assignable boolean not null
);
insert into nfc_tag_statuses values
 ('ACTIVE','Active tag, available for assignment',true),
 ('DELETED','Soft-deleted tag; history is retained',false);
alter table nfc_tags add column status varchar(30) not null default 'ACTIVE' references nfc_tag_statuses(code);
update nfc_tags set status=case when enabled then 'ACTIVE' else 'DELETED' end;
create function nwtrack_tag_lifecycle() returns trigger language plpgsql as $$
declare usable boolean;
begin
 select assignable into usable from nfc_tag_statuses where code=new.status;
 if usable is null then raise exception 'Unknown NFC tag status' using errcode='23514'; end if;
 if not usable and new.active then
  raise exception 'Unassign the NFC tag before changing its lifecycle status' using errcode='23514';
 end if;
 -- enabled is a compatibility implementation detail, not the public status.
 new.enabled := usable;
 return new;
end $$;
create trigger trg_00_tag_lifecycle before insert or update on nfc_tags
for each row execute function nwtrack_tag_lifecycle();
