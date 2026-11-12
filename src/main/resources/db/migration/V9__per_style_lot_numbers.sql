alter table styles add column last_lot_sequence bigint not null default 0 check(last_lot_sequence>=0);
alter table lots add column lot_sequence bigint check(lot_sequence>0);
create unique index uq_lots_style_sequence on lots(style_id,lot_sequence);

-- Keep all historical numbers unchanged. Continue each style past both its
-- historical generated suffixes and its existing lot count (including cancelled).
update styles s set last_lot_sequence=greatest(
 (select count(*) from lots l where l.style_id=s.id),
 coalesce((select max(substring(l.lot_number from '^LOT-[0-9]+-([0-9]{1,18})$')::bigint)
           from lots l where l.style_id=s.id),0));

-- A factory-wide logical epoch millisecond makes overlapping style counters
-- collision-free, even under concurrent requests or a backwards clock adjustment.
create table lot_number_clock (
 singleton boolean primary key default true check(singleton),
 last_epoch_ms bigint not null
);
insert into lot_number_clock(singleton,last_epoch_ms)
select true,greatest(floor(extract(epoch from clock_timestamp())*1000)::bigint,
 coalesce(max(substring(lot_number from '^LOT-([0-9]{1,18})-[0-9]+$')::bigint),0)) from lots;
