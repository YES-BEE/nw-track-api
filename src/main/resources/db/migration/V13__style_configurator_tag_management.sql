insert into role_modules(role_id,module_key)
select id,'tags' from roles where code='STYLE_CONFIGURATOR'
on conflict do nothing;
