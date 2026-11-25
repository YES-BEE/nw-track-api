create index if not exists ix_lots_created_at on lots(created_at,id);
create index if not exists ix_lots_completed_at on lots(completed_at,id) where completed_at is not null;
create index if not exists ix_station_visits_dispatched_at on station_visits(dispatched_at,lot_id) where dispatched_at is not null;
create index if not exists ix_station_visits_received_at on station_visits(received_at,lot_id) where received_at is not null;
create index if not exists ix_process_executions_started_at on process_executions(started_at,station_visit_id) where started_at is not null;
create index if not exists ix_process_executions_completed_at on process_executions(completed_at,station_visit_id) where completed_at is not null;
create index if not exists ix_storage_occupancies_stored_at on storage_occupancies(stored_at,lot_id);
create index if not exists ix_lot_trolley_assignments_linked_at on lot_trolley_assignments(linked_at,lot_id);

create table report_categories (
  id uuid primary key default gen_random_uuid(),
  report_key varchar(80) not null unique,
  display_name varchar(150) not null,
  description varchar(500) not null,
  display_order integer not null unique check(display_order>0),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

insert into report_categories(report_key,display_name,description,display_order) values
 ('style-lot-production','Style & Lot Production','Historical lot progress, location, status and completion duration',1),
 ('station-processing-time','Station Processing Time','Process execution durations and station timing aggregates',2),
 ('delay-bottleneck','Delay & Bottleneck Analysis','Actual process durations compared with configured targets',3),
 ('lot-movement-traceability','Lot Movement & Traceability','Historical lot movements, receipts, trolleys and storage locations',4),
 ('storage-waiting-time','Intermediate Storage & Waiting Time','Storage occupancy and waiting durations',5),
 ('trolley-utilization','Trolley Utilization','Historical trolley assignments and association durations',6),
 ('operator-activity','Operator Activity','Process executions and completion totals by operator',7),
 ('daily-production-summary','Daily Production Summary','Daily lot production metrics and station output',8);

create table report_export_history (
  id uuid primary key,
  report_category_id uuid not null references report_categories(id),
  requested_by uuid not null references users(id),
  format varchar(10) not null check(format in ('XLSX','CSV','PDF')),
  from_date date not null,
  to_date date not null,
  filters jsonb not null default '{}'::jsonb,
  status varchar(20) not null check(status in ('REQUESTED','COMPLETED','FAILED')),
  requested_at timestamptz not null default now(),
  completed_at timestamptz,
  failure_message varchar(1000),
  check(from_date<=to_date),
  check((status='REQUESTED' and completed_at is null and failure_message is null)
     or (status='COMPLETED' and completed_at is not null and failure_message is null)
     or (status='FAILED' and completed_at is not null and failure_message is not null))
);

create index ix_report_export_history_requester on report_export_history(requested_by,requested_at desc);
create index ix_report_export_history_category on report_export_history(report_category_id,requested_at desc);
