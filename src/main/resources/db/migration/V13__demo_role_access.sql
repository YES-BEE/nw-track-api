insert into role_modules(role_id,module_key)
select r.id,m.key
from roles r
cross join app_modules m
where r.code='STYLE_CONFIGURATOR'
  and m.key in ('newstyle','lots','process','tags','storage','trolleys')
on conflict do nothing;

update roles
set pc_access=true,pad_access=true
where code='STYLE_CONFIGURATOR';

insert into role_permissions(role_id,permission)
select id,permission
from roles
cross join (values ('READ'),('CONFIGURE')) granted(permission)
where code='STYLE_CONFIGURATOR'
on conflict do nothing;
