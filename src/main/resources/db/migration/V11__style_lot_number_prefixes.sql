-- Reserve sanitized prefixes without renaming any existing style or lot.
-- For legacy styles that sanitize identically, the earliest style owns the prefix.
create table style_lot_number_prefixes (
  prefix varchar(80) primary key,
  style_id uuid not null unique references styles(id)
);
insert into style_lot_number_prefixes(prefix,style_id)
select distinct on (prefix) prefix,id from (
  select id,created_at,regexp_replace(style_number,'[^A-Za-z0-9]','','g') prefix from styles
) normalized where prefix<>'' order by prefix,created_at,id;

-- Allow an 80-character style prefix plus the sequence, including bigint growth.
alter table lots alter column lot_number type varchar(110);
