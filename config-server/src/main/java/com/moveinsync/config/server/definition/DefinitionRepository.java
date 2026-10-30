package com.moveinsync.config.server.definition;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DefinitionRepository extends JpaRepository<DefinitionEntity, String> {
}
