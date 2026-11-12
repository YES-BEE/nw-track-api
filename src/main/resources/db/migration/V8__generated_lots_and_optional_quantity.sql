create sequence lot_number_sequence;
alter table styles add column lot_count integer not null default 0 check(lot_count>=0);
alter table styles add column lot_quantity integer check(lot_quantity>0);
alter table lots alter column quantity drop not null;
alter table lots alter column original_quantity drop not null;
alter table lot_quantity_changes alter column old_quantity drop not null;
alter table lot_trolley_assignments add column previous_location_type varchar(30);
alter table lot_trolley_assignments add column previous_location_id uuid;
