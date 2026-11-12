package com.example.Digital.Banking.System.Repository;

import com.example.Digital.Banking.System.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User,Long> {
    Boolean existsByEmail(String Email);
    Boolean existsById(String Id);
    Optional<User> findByEmail(String email);
}
