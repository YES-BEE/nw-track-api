update roles set pad_access=true where code='STYLE_CONFIGURATOR';
insert into role_modules(role_id,module_key)
select id,'trolleys' from roles where code='STYLE_CONFIGURATOR'
on conflict do nothing;
