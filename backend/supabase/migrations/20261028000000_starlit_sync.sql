-- Starlit-owned data only. Auth/session/password handling belongs to Supabase Auth.
create schema if not exists starlit_private;
revoke all on schema starlit_private from public, anon, authenticated;

create table starlit_private.profiles (
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_index integer not null check (profile_index between 1 and 6),
  id uuid not null default gen_random_uuid(),
  name text not null default 'Profile' check (length(name) between 1 and 100),
  avatar_color_hex text not null default '#1E88E5' check (avatar_color_hex ~ '^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$'),
  avatar_id text,
  avatar_url text,
  profile_background_id text,
  profile_background_url text,
  uses_primary_addons boolean not null default false,
  uses_primary_plugins boolean not null default false,
  pin_enabled boolean not null default false check (pin_enabled = false),
  pin_locked_until timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, profile_index)
);

create table starlit_private.records (
  user_id uuid not null,
  profile_id integer not null,
  kind text not null check (kind in ('library','watch_progress','watched_items','collections','home_catalog_settings','profile_settings_blob','provider_credentials')),
  record_key text not null check (length(record_key) between 1 and 8192),
  payload jsonb not null check (octet_length(payload::text) <= 1048576),
  updated_at timestamptz not null default now(),
  primary key (user_id, profile_id, kind, record_key),
  foreign key (user_id, profile_id) references starlit_private.profiles(user_id, profile_index) on delete cascade
);

create table starlit_private.events (
  event_id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_id integer not null check (profile_id between 1 and 6),
  kind text not null check (kind in ('library','watch_progress','watched_items')),
  operation text not null check (operation in ('upsert','delete')),
  payload jsonb not null,
  created_at timestamptz not null default now()
);
create index sync_delta_lookup on starlit_private.events(user_id, profile_id, kind, event_id);

create table public.addons (
  user_id uuid not null,
  profile_id integer not null,
  url text not null check (length(url) between 1 and 8192),
  name text not null default '' check (length(name) <= 512),
  enabled boolean not null default true,
  sort_order integer not null default 0,
  primary key (user_id, profile_id, url),
  foreign key (user_id, profile_id) references starlit_private.profiles(user_id, profile_index) on delete cascade
);
create table public.plugins (like public.addons including all);
alter table public.plugins add foreign key (user_id, profile_id) references starlit_private.profiles(user_id, profile_index) on delete cascade;

create table starlit_private.devices (
  user_id uuid not null references auth.users(id) on delete cascade,
  installation_id text not null check (length(installation_id) between 1 and 128),
  client_name text, client_version text, platform text, device_name text,
  updated_at timestamptz not null default now(),
  primary key(user_id, installation_id)
);

-- Defense in depth even for tables which PostgREST cannot see.
alter table starlit_private.profiles enable row level security;
alter table starlit_private.records enable row level security;
alter table starlit_private.events enable row level security;
alter table starlit_private.devices enable row level security;
alter table public.addons enable row level security;
alter table public.plugins enable row level security;
create policy own_profile on starlit_private.profiles using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy own_record on starlit_private.records using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy own_event on starlit_private.events using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy own_device on starlit_private.devices using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy own_addons on public.addons for select to authenticated using (user_id = (select auth.uid()));
create policy own_plugins on public.plugins for select to authenticated using (user_id = (select auth.uid()));
revoke all on public.addons, public.plugins from public, anon, authenticated;
grant select on public.addons, public.plugins to authenticated;

create function starlit_private.require_user() returns uuid language plpgsql set search_path = '' as $$
declare u uuid := auth.uid();
begin
  if u is null or coalesce((auth.jwt()->>'is_anonymous')::boolean, false) then
    raise exception 'An authenticated email account is required' using errcode = '42501';
  end if;
  return u;
end $$;

create function starlit_private.lock_user() returns uuid language plpgsql set search_path = '' as $$
declare u uuid := starlit_private.require_user();
begin
  -- Serialize this account's writes through commit: a cursor cannot skip a later-committing lower event id.
  perform pg_advisory_xact_lock(hashtextextended(u::text, 0));
  return u;
end $$;

create function starlit_private.require_profile(p_profile_id integer) returns uuid language plpgsql set search_path = '' as $$
declare u uuid := starlit_private.require_user();
begin
  if p_profile_id is null or p_profile_id not between 1 and 6 or not exists (
    select 1 from starlit_private.profiles where user_id=u and profile_index=p_profile_id
  ) then raise exception 'Profile does not exist for this account' using errcode='22023'; end if;
  return u;
end $$;

create function starlit_private.require_json(p_value jsonb, p_type text, p_max_bytes integer default 1048576) returns void language plpgsql set search_path='' as $$
begin
  if p_value is null or jsonb_typeof(p_value) <> p_type or octet_length(p_value::text) > p_max_bytes then
    raise exception 'Invalid or oversized % payload', p_type using errcode='22023';
  end if;
  if p_type='array' and jsonb_array_length(p_value)>1000 then
    raise exception 'At most 1000 items per request' using errcode='22023';
  end if;
end $$;

-- Validate serialized primitive fields so malformed clients cannot poison another device's decoder.
create function starlit_private.validate_fields(p_item jsonb, p_strings text[], p_numbers text[], p_booleans text[]) returns void language plpgsql set search_path='' as $$
declare k text;
begin
  perform starlit_private.require_json(p_item, 'object');
  foreach k in array p_strings loop
    if p_item ? k and jsonb_typeof(p_item->k) not in ('string','null') then
      raise exception 'Expected string: %', k using errcode='22023';
    end if;
  end loop;
  foreach k in array p_numbers loop
    if p_item ? k and jsonb_typeof(p_item->k) not in ('number','null') then
      raise exception 'Expected number: %', k using errcode='22023';
    end if;
  end loop;
  foreach k in array p_booleans loop
    if p_item ? k and jsonb_typeof(p_item->k) <> 'boolean' then
      raise exception 'Expected boolean: %', k using errcode='22023';
    end if;
  end loop;
end $$;

create function starlit_private.new_account_profile() returns trigger language plpgsql security definer set search_path='' as $$
begin
  insert into starlit_private.profiles(user_id, profile_index, name) values (new.id, 1, 'Profile 1');
  return new;
end $$;
create trigger starlit_new_account after insert on auth.users for each row execute function starlit_private.new_account_profile();

create function public.sync_pull_profiles() returns setof jsonb language plpgsql security definer set search_path='' as $$
declare u uuid := starlit_private.lock_user();
begin
  insert into starlit_private.profiles(user_id,profile_index,name) values(u,1,'Profile 1') on conflict do nothing;
  return query select to_jsonb(p) from starlit_private.profiles p where user_id=u order by profile_index;
end $$;

create function public.sync_push_profiles(p_profiles jsonb, p_client_max_profiles integer default 6, p_origin_client_id text default null) returns void language plpgsql security definer set search_path='' as $$
declare u uuid := starlit_private.lock_user(); item jsonb; n integer;
begin
  perform starlit_private.require_json(p_profiles,'array',65536);
  if p_client_max_profiles is null or p_client_max_profiles not between 1 and 6 or jsonb_array_length(p_profiles)>p_client_max_profiles then
    raise exception 'Profile limit is six' using errcode='22023';
  end if;
  for item in select value from jsonb_array_elements(p_profiles) loop
    perform starlit_private.validate_fields(item, array['name','avatar_color_hex','avatar_id','avatar_url','profile_background_id','profile_background_url'],array['profile_index'],array['uses_primary_addons','uses_primary_plugins']);
    n := (item->>'profile_index')::integer;
    if n is null or n not between 1 and p_client_max_profiles or coalesce(length(item->>'name'),0) not between 1 and 100 then
      raise exception 'Invalid profile' using errcode='22023';
    end if;
    if length(coalesce(item->>'avatar_url',''))>2048 or length(coalesce(item->>'profile_background_url',''))>2048 then
      raise exception 'Profile image URL too long' using errcode='22023';
    end if;
    insert into starlit_private.profiles(user_id,profile_index,name,avatar_color_hex,avatar_id,avatar_url,profile_background_id,profile_background_url,uses_primary_addons,uses_primary_plugins)
    values(u,n,item->>'name',coalesce(item->>'avatar_color_hex','#1E88E5'),item->>'avatar_id',item->>'avatar_url',item->>'profile_background_id',item->>'profile_background_url',coalesce((item->>'uses_primary_addons')::boolean,false),coalesce((item->>'uses_primary_plugins')::boolean,false))
    on conflict(user_id,profile_index) do update set name=excluded.name,avatar_color_hex=excluded.avatar_color_hex,avatar_id=excluded.avatar_id,avatar_url=excluded.avatar_url,profile_background_id=excluded.profile_background_id,profile_background_url=excluded.profile_background_url,uses_primary_addons=excluded.uses_primary_addons,uses_primary_plugins=excluded.uses_primary_plugins,updated_at=now();
  end loop;
  -- Deliberately do not delete omitted profiles. Clients use explicit sync_delete_profile_data.
end $$;

create function public.sync_delete_profile_data(p_profile_id integer, p_origin_client_id text default null) returns void language plpgsql security definer set search_path='' as $$
declare u uuid := starlit_private.lock_user(); r record;
begin
  perform starlit_private.require_profile(p_profile_id);
  if p_profile_id=1 then raise exception 'The primary profile cannot be deleted' using errcode='22023'; end if;
  -- Keep tombstones if the index is later reused, so other devices can remove stale records.
  for r in select kind,payload from starlit_private.records where user_id=u and profile_id=p_profile_id and kind in ('library','watch_progress','watched_items') loop
    insert into starlit_private.events(user_id,profile_id,kind,operation,payload) values(u,p_profile_id,r.kind,'delete',r.payload);
  end loop;
  delete from starlit_private.profiles where user_id=u and profile_index=p_profile_id;
end $$;

create function public.sync_pull_profile_locks() returns table(profile_index integer,pin_enabled boolean,pin_locked_until timestamptz) language plpgsql security definer set search_path='' as $$
declare u uuid := starlit_private.require_user();
begin return query select p.profile_index,false,null::timestamptz from starlit_private.profiles p where p.user_id=u order by p.profile_index; end $$;

create function public.verify_profile_pin(p_profile_id integer,p_pin text) returns table(unlocked boolean,retry_after_seconds integer,message text) language plpgsql security definer set search_path='' as $$
begin
  perform starlit_private.require_profile(p_profile_id);
  return query select false,0,'Profile PINs are not supported by this Starlit server'::text;
end $$;
create function public.set_profile_pin(p_profile_id integer,p_pin text,p_current_pin text default null) returns void language plpgsql security definer set search_path='' as $$
begin perform starlit_private.require_profile(p_profile_id); raise exception 'Profile PINs are not supported by this Starlit server' using errcode='0A000'; end $$;
create function public.clear_profile_pin(p_profile_id integer,p_current_pin text default null) returns void language plpgsql security definer set search_path='' as $$
begin perform starlit_private.require_profile(p_profile_id); raise exception 'Profile PINs are not supported by this Starlit server' using errcode='0A000'; end $$;
create function public.clear_profile_pin_with_account_password(p_profile_id integer,p_account_password text) returns void language plpgsql security definer set search_path='' as $$
begin perform starlit_private.require_profile(p_profile_id); raise exception 'Profile PINs are not supported by this Starlit server' using errcode='0A000'; end $$;

create function starlit_private.push_extensions(p_kind text,p_profile_id integer,p_items jsonb) returns void language plpgsql set search_path='' as $$
declare u uuid := starlit_private.lock_user(); item jsonb; target regclass;
begin
  perform starlit_private.require_profile(p_profile_id);
  perform starlit_private.require_json(p_items,'array');
  target := case p_kind when 'addons' then 'public.addons'::regclass when 'plugins' then 'public.plugins'::regclass end;
  if target is null then raise exception 'Invalid extension kind' using errcode='22023'; end if;
  -- These two client RPCs send complete lists, so replacement preserves removal and ordering.
  execute format('delete from %s where user_id=$1 and profile_id=$2',target) using u,p_profile_id;
  for item in select value from jsonb_array_elements(p_items) loop
    perform starlit_private.validate_fields(item,array['url','name'],array['sort_order'],array['enabled']);
    if jsonb_typeof(item->'url') is distinct from 'string' then raise exception 'Missing extension URL' using errcode='22023'; end if;
    execute format('insert into %s(user_id,profile_id,url,name,enabled,sort_order) values($1,$2,$3,$4,$5,$6) on conflict(user_id,profile_id,url) do update set name=excluded.name,enabled=excluded.enabled,sort_order=excluded.sort_order',target)
    using u,p_profile_id,item->>'url',coalesce(item->>'name',''),coalesce((item->>'enabled')::boolean,true),coalesce((item->>'sort_order')::integer,0);
  end loop;
end $$;
create function public.sync_push_addons(p_profile_id integer,p_addons jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_extensions('addons',p_profile_id,p_addons) $$;
create function public.sync_push_plugins(p_profile_id integer,p_plugins jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_extensions('plugins',p_profile_id,p_plugins) $$;

create function starlit_private.item_key(p_kind text,p_item jsonb,p_delete boolean default false) returns text language plpgsql set search_path='' as $$
declare k text;
begin
  if p_kind='watch_progress' and p_delete then
    if jsonb_typeof(p_item) is distinct from 'string' then raise exception 'Expected progress key string' using errcode='22023'; end if;
    k:=p_item#>>'{}';
  else
    perform starlit_private.require_json(p_item,'object');
    if p_kind='watch_progress' then
      k:=p_item->>'progress_key';
    else
      if jsonb_typeof(p_item->'content_id') is distinct from 'string' or length(p_item->>'content_id') not between 1 and 2048 then
        raise exception 'Missing content_id' using errcode='22023';
      end if;
      if p_kind='library' then
        if jsonb_typeof(p_item->'content_type') is distinct from 'string' or length(p_item->>'content_type') not between 1 and 100 then
          raise exception 'Missing content_type' using errcode='22023';
        end if;
        k:=jsonb_build_array(p_item->>'content_id',p_item->>'content_type')::text;
      elsif p_kind='watched_items' then
        -- The client deletes by content_id/season/episode (without content_type).
        k:=jsonb_build_array(p_item->>'content_id',p_item->>'season',p_item->>'episode')::text;
      else raise exception 'Invalid sync kind' using errcode='22023'; end if;
    end if;
  end if;
  if k is null or length(k) not between 1 and 8192 then raise exception 'Invalid record key' using errcode='22023'; end if;
  return k;
end $$;

create function starlit_private.push_items(p_kind text,p_profile_id integer,p_items jsonb,p_delete boolean default false) returns void language plpgsql set search_path='' as $$
declare u uuid := starlit_private.lock_user(); item jsonb; k text; old_payload jsonb; payload jsonb; timestamp_field text;
begin
  perform starlit_private.require_profile(p_profile_id);
  perform starlit_private.require_json(p_items,'array');
  if p_kind not in ('library','watch_progress','watched_items') then raise exception 'Invalid sync kind' using errcode='22023'; end if;
  timestamp_field:=case p_kind when 'watch_progress' then 'last_watched' when 'watched_items' then 'watched_at' else 'added_at' end;
  for item in select value from jsonb_array_elements(p_items) loop
    k:=starlit_private.item_key(p_kind,item,p_delete);
    select r.payload into old_payload from starlit_private.records r where user_id=u and profile_id=p_profile_id and kind=p_kind and record_key=k;
    if p_delete then
      if old_payload is null then continue; end if;
      delete from starlit_private.records where user_id=u and profile_id=p_profile_id and kind=p_kind and record_key=k;
      payload:=old_payload;
    else
      perform starlit_private.validate_fields(item,array['content_id','content_type','name','title','poster','poster_shape','background','logo','description','release_info','addon_base_url','video_id','progress_key'],array['season','episode','position','duration','last_watched','watched_at','added_at','imdb_rating'],array[]::text[]);
      if jsonb_typeof(item->'content_id') is distinct from 'string' or jsonb_typeof(item->'content_type') is distinct from 'string' or length(item->>'content_id') not between 1 and 2048 or length(item->>'content_type') not between 1 and 100 then
        raise exception 'Missing content_id/content_type' using errcode='22023';
      end if;
      if p_kind='library' then
        if item ? 'genres' then
          perform starlit_private.require_json(item->'genres','array',65536);
          if exists(select 1 from jsonb_array_elements(item->'genres') g where jsonb_typeof(g)<>'string') then raise exception 'Genres must be strings' using errcode='22023'; end if;
        end if;
        payload:=jsonb_build_object('name','','poster_shape','POSTER','genres','[]'::jsonb,'added_at',0)||jsonb_strip_nulls(item);
      elsif p_kind='watch_progress' then
        if jsonb_typeof(item->'video_id') is distinct from 'string' then raise exception 'Missing video_id' using errcode='22023'; end if;
        payload:=jsonb_build_object('position',0,'duration',0,'last_watched',0)||jsonb_strip_nulls(item);
      else payload:=jsonb_build_object('title','','watched_at',0)||jsonb_strip_nulls(item);
      end if;
      if (item ? 'season' and jsonb_typeof(item->'season')<>'null' and ((item->>'season')::numeric not between 0 and 2147483647 or (item->>'season')::numeric<>trunc((item->>'season')::numeric))) or
         (item ? 'episode' and jsonb_typeof(item->'episode')<>'null' and ((item->>'episode')::numeric not between 0 and 2147483647 or (item->>'episode')::numeric<>trunc((item->>'episode')::numeric))) then
        raise exception 'Invalid season/episode' using errcode='22023';
      end if;
      -- Epoch millisecond and position fields must decode as nonnegative Kotlin Longs.
      if coalesce((payload->>timestamp_field)::numeric,-1) not between 0 and 9223372036854775807 or (payload->>timestamp_field)::numeric<>trunc((payload->>timestamp_field)::numeric) then
        raise exception 'Invalid timestamp' using errcode='22023';
      end if;
      if p_kind='watch_progress' and (coalesce((payload->>'position')::numeric,-1) not between 0 and 9223372036854775807 or coalesce((payload->>'duration')::numeric,-1) not between 0 and 9223372036854775807 or (payload->>'position')::numeric<>trunc((payload->>'position')::numeric) or (payload->>'duration')::numeric<>trunc((payload->>'duration')::numeric)) then
        raise exception 'Invalid position/duration' using errcode='22023';
      end if;
      if coalesce((old_payload->>timestamp_field)::bigint,-1)>(payload->>timestamp_field)::bigint then continue; end if;
      if old_payload=payload then continue; end if;
      insert into starlit_private.records(user_id,profile_id,kind,record_key,payload) values(u,p_profile_id,p_kind,k,payload)
      on conflict(user_id,profile_id,kind,record_key) do update set payload=excluded.payload,updated_at=now();
    end if;
    insert into starlit_private.events(user_id,profile_id,kind,operation,payload) values(u,p_profile_id,p_kind,case when p_delete then 'delete' else 'upsert' end,payload);
  end loop;
end $$;

create function starlit_private.pull_items(p_kind text,p_profile_id integer,p_limit integer,p_offset integer default 0,p_since_last_watched bigint default null) returns setof jsonb language plpgsql set search_path='' as $$
declare u uuid := starlit_private.require_profile(p_profile_id);
begin
  if p_limit is null or p_limit not between 1 and 1000 or p_offset is null or p_offset<0 then raise exception 'Invalid pagination' using errcode='22023'; end if;
  return query select payload from starlit_private.records where user_id=u and profile_id=p_profile_id and kind=p_kind
    and (p_since_last_watched is null or (payload->>'last_watched')::bigint>p_since_last_watched)
    order by record_key limit p_limit offset p_offset;
end $$;

create function starlit_private.delta_cursor(p_kind text,p_profile_id integer) returns bigint language plpgsql set search_path='' as $$
declare u uuid := starlit_private.require_profile(p_profile_id);
begin return (select coalesce(max(event_id),0) from starlit_private.events where user_id=u and profile_id=p_profile_id and kind=p_kind); end $$;

create function starlit_private.pull_delta(p_kind text,p_profile_id integer,p_since_event_id bigint,p_limit integer) returns setof jsonb language plpgsql set search_path='' as $$
declare u uuid := starlit_private.require_profile(p_profile_id);
begin
  if p_limit is null or p_limit not between 1 and 1000 or p_since_event_id is null or p_since_event_id<0 then raise exception 'Invalid delta pagination' using errcode='22023'; end if;
  return query select payload||jsonb_build_object('event_id',event_id,'operation',operation) from starlit_private.events
    where user_id=u and profile_id=p_profile_id and kind=p_kind and event_id>p_since_event_id order by event_id limit p_limit;
end $$;

-- Exact Kotlin RPC names and named parameters, including optional origin-client metadata.
create function public.sync_pull_library(p_profile_id integer,p_limit integer default 500,p_offset integer default 0) returns setof jsonb language sql security definer set search_path='' as $$ select * from starlit_private.pull_items('library',p_profile_id,p_limit,p_offset) $$;
-- This client snapshot has no offset/page parameter. A scalar JSON array avoids PostgREST's
-- max_rows truncation while preserving the exact JSON array consumed by decodeList().
create function public.sync_pull_watch_progress(p_profile_id integer,p_since_last_watched bigint default null,p_limit integer default null) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid := starlit_private.require_profile(p_profile_id);
begin
  if (p_limit is not null and p_limit not between 1 and 1000) or (p_since_last_watched is not null and p_since_last_watched<0) then
    raise exception 'Invalid progress filter/limit' using errcode='22023';
  end if;
  return (select coalesce(jsonb_agg(s.payload),'[]'::jsonb) from (
    select payload from starlit_private.records where user_id=u and profile_id=p_profile_id and kind='watch_progress'
      and (p_since_last_watched is null or (payload->>'last_watched')::bigint>p_since_last_watched)
      order by record_key limit p_limit
  ) s);
end $$;
create function public.sync_pull_watched_items(p_profile_id integer,p_page integer default 1,p_page_size integer default 500) returns setof jsonb language plpgsql security definer set search_path='' as $$
begin
  if p_page is null or p_page<1 or p_page>2147483 then raise exception 'Invalid page' using errcode='22023'; end if;
  return query select * from starlit_private.pull_items('watched_items',p_profile_id,p_page_size,(p_page-1)*p_page_size);
end $$;

create function public.sync_push_library_items(p_profile_id integer,p_items jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_items('library',p_profile_id,p_items) $$;
create function public.sync_delete_library_items(p_profile_id integer,p_keys jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_items('library',p_profile_id,p_keys,true) $$;
create function public.sync_push_watch_progress(p_profile_id integer,p_entries jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_items('watch_progress',p_profile_id,p_entries) $$;
create function public.sync_delete_watch_progress(p_profile_id integer,p_keys jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_items('watch_progress',p_profile_id,p_keys,true) $$;
create function public.sync_push_watched_items(p_profile_id integer,p_items jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_items('watched_items',p_profile_id,p_items) $$;
create function public.sync_delete_watched_items(p_profile_id integer,p_keys jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_items('watched_items',p_profile_id,p_keys,true) $$;

create function public.sync_get_library_delta_cursor(p_profile_id integer) returns bigint language sql security definer set search_path='' as $$ select starlit_private.delta_cursor('library',p_profile_id) $$;
create function public.sync_get_watch_progress_delta_cursor(p_profile_id integer) returns bigint language sql security definer set search_path='' as $$ select starlit_private.delta_cursor('watch_progress',p_profile_id) $$;
create function public.sync_get_watched_items_delta_cursor(p_profile_id integer) returns bigint language sql security definer set search_path='' as $$ select starlit_private.delta_cursor('watched_items',p_profile_id) $$;
create function public.sync_pull_library_delta(p_profile_id integer,p_since_event_id bigint,p_limit integer default 500) returns setof jsonb language sql security definer set search_path='' as $$ select * from starlit_private.pull_delta('library',p_profile_id,p_since_event_id,p_limit) $$;
create function public.sync_pull_watch_progress_delta(p_profile_id integer,p_since_event_id bigint,p_limit integer default 500) returns setof jsonb language sql security definer set search_path='' as $$ select * from starlit_private.pull_delta('watch_progress',p_profile_id,p_since_event_id,p_limit) $$;
create function public.sync_pull_watched_items_delta(p_profile_id integer,p_since_event_id bigint,p_limit integer default 500) returns setof jsonb language sql security definer set search_path='' as $$ select * from starlit_private.pull_delta('watched_items',p_profile_id,p_since_event_id,p_limit) $$;

create function starlit_private.push_blob(p_kind text,p_profile_id integer,p_key text,p_payload jsonb) returns void language plpgsql set search_path='' as $$
declare u uuid := starlit_private.lock_user();
begin
  perform starlit_private.require_profile(p_profile_id);
  if p_kind='collections' then perform starlit_private.require_json(p_payload,'array');
  elsif p_kind in ('profile_settings_blob','home_catalog_settings') then
    perform starlit_private.require_json(p_payload,'object');
    if p_key not in ('mobile','desktop','home_catalog_shared') or p_key is null or (p_kind='profile_settings_blob' and p_key='home_catalog_shared') then
      raise exception 'Invalid settings platform' using errcode='22023';
    end if;
  else raise exception 'Invalid blob kind' using errcode='22023'; end if;
  if p_kind='profile_settings_blob' then
    -- Provider keys have a dedicated isolation boundary; do not duplicate them into ordinary settings.
    p_payload:=p_payload #- '{features,player_settings,animeskip_client_id}' #- '{features,player_settings,introdb_api_key}'
      #- '{features,debrid_settings,debrid_torbox_api_key}' #- '{features,debrid_settings,debrid_premiumize_api_key}'
      #- '{features,debrid_settings,debrid_real_debrid_api_key}' #- '{features,tmdb_settings,tmdb_api_key}'
      #- '{features,mdblist_settings,mdblist_api_key}';
  end if;
  insert into starlit_private.records(user_id,profile_id,kind,record_key,payload) values(u,p_profile_id,p_kind,p_key,p_payload)
  on conflict(user_id,profile_id,kind,record_key) do update set payload=excluded.payload,updated_at=now();
end $$;

create function starlit_private.pull_blob(p_kind text,p_profile_id integer,p_key text,p_payload_field text) returns setof jsonb language plpgsql set search_path='' as $$
declare u uuid := starlit_private.require_profile(p_profile_id);
begin
  return query select jsonb_build_object('profile_id',profile_id,p_payload_field,payload,'updated_at',updated_at)
    from starlit_private.records where user_id=u and profile_id=p_profile_id and kind=p_kind and record_key=p_key;
end $$;

create function public.sync_push_collections(p_profile_id integer,p_collections_json jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_blob('collections',p_profile_id,'collections',p_collections_json) $$;
create function public.sync_pull_collections(p_profile_id integer) returns setof jsonb language sql security definer set search_path='' as $$ select * from starlit_private.pull_blob('collections',p_profile_id,'collections','collections_json') $$;
create function public.sync_push_home_catalog_settings(p_profile_id integer,p_platform text,p_settings_json jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_blob('home_catalog_settings',p_profile_id,p_platform,p_settings_json) $$;
create function public.sync_pull_home_catalog_settings(p_profile_id integer,p_platform text) returns setof jsonb language sql security definer set search_path='' as $$ select * from starlit_private.pull_blob('home_catalog_settings',p_profile_id,p_platform,'settings_json') $$;
create function public.sync_push_profile_settings_blob(p_profile_id integer,p_platform text,p_settings_json jsonb,p_origin_client_id text default null) returns void language sql security definer set search_path='' as $$ select starlit_private.push_blob('profile_settings_blob',p_profile_id,p_platform,p_settings_json) $$;
create function public.sync_pull_profile_settings_blob(p_profile_id integer,p_platform text) returns setof jsonb language sql security definer set search_path='' as $$ select * from starlit_private.pull_blob('profile_settings_blob',p_profile_id,p_platform,'settings_json') $$;

create function public.sync_push_provider_credentials(p_profile_id integer,p_credentials jsonb,p_origin_client_id text default null) returns void language plpgsql security definer set search_path='' as $$
declare u uuid := starlit_private.lock_user(); item jsonb; provider text;
begin
  perform starlit_private.require_profile(p_profile_id);
  perform starlit_private.require_json(p_credentials,'array',262144);
  for item in select value from jsonb_array_elements(p_credentials) loop
    perform starlit_private.require_json(item,'object',65536);
    provider:=item->>'provider';
    if jsonb_typeof(item->'provider') is distinct from 'string' or provider !~ '^(tmdb|mdblist|animeskip|introdb|debrid:[a-z0-9_-]{1,64})$' then
      raise exception 'Invalid provider' using errcode='22023';
    end if;
    perform starlit_private.require_json(item->'credential_json','object',65536);
    if not (item->'credential_json' ? case when provider='animeskip' then 'client_id' else 'api_key' end) or
      exists(select 1 from jsonb_each(item->'credential_json') f where f.key not in ('api_key','client_id') or jsonb_typeof(f.value)<>'string') then
      raise exception 'Invalid credential fields' using errcode='22023';
    end if;
    insert into starlit_private.records(user_id,profile_id,kind,record_key,payload) values(u,p_profile_id,'provider_credentials',provider,item->'credential_json')
    on conflict(user_id,profile_id,kind,record_key) do update set payload=excluded.payload,updated_at=now();
  end loop;
end $$;
create function public.sync_pull_provider_credentials(p_profile_id integer) returns setof jsonb language plpgsql security definer set search_path='' as $$
declare u uuid := starlit_private.require_profile(p_profile_id);
begin
  return query select jsonb_build_object('provider',record_key,'credential_json',payload,'updated_at',updated_at)
    from starlit_private.records where user_id=u and profile_id=p_profile_id and kind='provider_credentials' order by record_key;
end $$;

create function public.register_current_device(p_installation_id text,p_client_name text,p_client_version text,p_platform text,p_device_name text) returns void language plpgsql security definer set search_path='' as $$
declare u uuid := starlit_private.lock_user();
begin
  if length(coalesce(p_client_name,''))>128 or length(coalesce(p_client_version,''))>64 or length(coalesce(p_platform,''))>64 or length(coalesce(p_device_name,''))>256 then raise exception 'Invalid device metadata' using errcode='22023'; end if;
  insert into starlit_private.devices(user_id,installation_id,client_name,client_version,platform,device_name)
  values(u,p_installation_id,p_client_name,p_client_version,p_platform,p_device_name)
  on conflict(user_id,installation_id) do update set client_name=excluded.client_name,client_version=excluded.client_version,platform=excluded.platform,device_name=excluded.device_name,updated_at=now();
end $$;

-- Default PostgreSQL/Supabase function grants must never expose internal helpers or anonymous sync.
revoke all on all tables in schema starlit_private from public, anon, authenticated;
revoke all on all sequences in schema starlit_private from public, anon, authenticated;
revoke all on all functions in schema starlit_private from public, anon, authenticated;
do $$ declare f record; begin
  for f in select p.oid::regprocedure as signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='public' and (p.proname like 'sync_%' or p.proname in ('verify_profile_pin','set_profile_pin','clear_profile_pin','clear_profile_pin_with_account_password','register_current_device'))
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f.signature);
    execute format('grant execute on function %s to authenticated', f.signature);
  end loop;
end $$;
