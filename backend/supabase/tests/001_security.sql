begin;
create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;
select plan(8);

select ok(not has_schema_privilege('authenticated','starlit_private','USAGE'), 'private schema is inaccessible to clients');
select ok(not has_schema_privilege('anon','starlit_private','USAGE'), 'private schema is inaccessible to anonymous requests');
select is((select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
  where (n.nspname='starlit_private' or (n.nspname='public' and c.relname in ('addons','plugins')))
  and c.relkind='r' and not c.relrowsecurity), 0::bigint, 'every Starlit table enables RLS');
select ok(not exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' and (p.proname like 'sync_%' or p.proname in ('verify_profile_pin','set_profile_pin','clear_profile_pin','clear_profile_pin_with_account_password','register_current_device'))
  and has_function_privilege('anon',p.oid,'EXECUTE')), 'anonymous users cannot execute any sync/account RPC');
select ok(not exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='starlit_private' and has_function_privilege('authenticated',p.oid,'EXECUTE')), 'private helpers cannot be invoked directly');
select ok(not exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
  where n.nspname='starlit_private' and c.relkind='r' and has_table_privilege('authenticated',c.oid,'SELECT,INSERT,UPDATE,DELETE')), 'no client table privilege exposes private sync/credential/device records');
select ok(not has_table_privilege('authenticated','public.addons','INSERT,UPDATE,DELETE')
  and not has_table_privilege('authenticated','public.plugins','INSERT,UPDATE,DELETE'), 'direct extension mutations cannot bypass validation and events');
select ok(not exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' and (p.proname like 'sync_%' or p.proname in ('verify_profile_pin','set_profile_pin','clear_profile_pin','clear_profile_pin_with_account_password','register_current_device'))
  and (not p.prosecdef or not coalesce(p.proconfig @> array['search_path=""'],false))), 'every exposed RPC has an explicit empty search path and controlled definer boundary');

select * from finish();
rollback;
