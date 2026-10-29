create user SuperManager password '1';
create user Manager password '2';
create user NormalUser password '3';
grant all on all tables in schema public to SuperManager;
grant insert,update,delete,select on users,videos,danmus,favorites,coins,videolikes,viewers,followings,danmulikes to Manager;
grant select on users,videos,danmus,favorites,coins,videolikes,viewers,followings,danmulikes to NormalUser;
grant update,insert on danmus,favorites,coins,videolikes,viewers,followings,danmulikes to NormalUser;
