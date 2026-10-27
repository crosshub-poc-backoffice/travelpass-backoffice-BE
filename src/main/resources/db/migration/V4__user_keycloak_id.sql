-- 백오피스 계정(User)과 Keycloak 사용자를 username(loginId) 말고 Keycloak 사용자 id(토큰의 sub)로도 묶는다.
-- username 만으로 찾으면, Keycloak 에서 사용자를 지우고 같은 이름으로 다시 만들었을 때(실수·탈취) 그 새 사용자가 옛 계정의 권한을 그대로 갖는다.
-- 발급 때 채우고, 이 열이 생기기 전 계정은 다음 로그인 때 처음 본 sub 로 묶는다 (CallerService). 한 번 묶이면 다른 sub 는 403.
-- 다시 묶어야 하면(정상적으로 Keycloak 사용자를 새로 만든 경우) 이 값을 NULL 로 비운다.
ALTER TABLE "User" ADD COLUMN "keycloakId" text;
CREATE UNIQUE INDEX "User_keycloakId_key" ON "User" ("keycloakId");
