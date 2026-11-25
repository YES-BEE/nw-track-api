create function queue_lot_progress_update() returns trigger language plpgsql as $$
declare target_lot uuid;
begin
  if tg_table_name = 'lots' then
    target_lot := new.id;
  elsif tg_table_name = 'process_executions' then
    select lot_id into target_lot from station_visits where id = new.station_visit_id;
  else
    target_lot := new.lot_id;
  end if;
  if target_lot is not null and exists (select 1 from outbox_events where event_type='INCOMING_LOT' and aggregate_id=target_lot) then
    insert into outbox_events(id,aggregate_type,aggregate_id,aggregate_version,event_type,payload)
    values (gen_random_uuid(),'LOT',target_lot,1,'LOT_UPDATED',jsonb_build_object('lotId',target_lot));
  end if;
  return new;
end $$;

create trigger lot_progress_lot after update on lots for each row execute function queue_lot_progress_update();
create trigger lot_progress_process after update on process_executions for each row when (old.status is distinct from new.status) execute function queue_lot_progress_update();
create trigger lot_progress_step after update on lot_route_entries for each row when (old.status is distinct from new.status) execute function queue_lot_progress_update();
