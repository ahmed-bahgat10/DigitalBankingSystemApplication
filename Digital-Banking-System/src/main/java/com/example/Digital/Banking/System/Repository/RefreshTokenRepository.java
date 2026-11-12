package com.example.Digital.Banking.System.Repository;

import com.example.Digital.Banking.System.Entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken,Long> {
}
