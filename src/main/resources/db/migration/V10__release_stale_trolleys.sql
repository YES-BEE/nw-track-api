-- Repair trolleys left IN_USE by cancellations before the release fix.
update trolleys t
set status=case when active then 'AVAILABLE' else 'INACTIVE' end,
    version=version+1
where status='IN_USE'
  and not exists (
    select 1 from lot_trolley_assignments a
    where a.trolley_id=t.id and a.released_at is null
  );
