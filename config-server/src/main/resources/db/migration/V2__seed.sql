-- demo users; passwords are admin / ops / ops / emp / svc (BCrypt, cost 10)
insert into app_user (username, password_hash, role, tenant_id) values
    ('admin',       '$2a$10$2Wi5Mno3BdFxDhITNge7AecleYzJ5ef8S5xsI5Yna8Vn6qHTKnAYS', 'ADMIN',    null),
    ('acme-ops',    '$2a$10$kzCY8XKe1YBmMuBsqZ91fucqKEUU9rFH2LkCyarOYSRovWHPYAIj.', 'OPERATOR', 'acme'),
    ('globex-ops',  '$2a$10$kzCY8XKe1YBmMuBsqZ91fucqKEUU9rFH2LkCyarOYSRovWHPYAIj.', 'OPERATOR', 'globex'),
    ('acme-emp',    '$2a$10$K0wkgDeMMFrHGIq0AsgT7OJexpusU.B1ELmwHluzn8hkBIYUJjfC6', 'EMPLOYEE', 'acme'),
    ('booking-svc', '$2a$10$qc1ia7XZoNX6izakG5mKMuiU1kUctyD4rZIl4lxyHc/lnXVNEZX.6', 'SERVICE',  null);
