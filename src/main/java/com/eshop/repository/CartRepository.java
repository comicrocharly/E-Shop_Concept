package com.eshop.repository;

import com.eshop.entity.Cart;
import com.eshop.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    @Query("SELECT c FROM Cart c JOIN FETCH c.items i JOIN FETCH i.articles WHERE c.user = :user")
    Optional<Cart> findByUserWithItems(User user);
    
    @Query("SELECT c FROM Cart c JOIN FETCH c.items i JOIN FETCH i.articles WHERE c.user.username = :username")
    Optional<Cart> findByUsernameWithItems(String username);
    
    @Query("SELECT c FROM Cart c JOIN FETCH c.items i JOIN FETCH i.articles WHERE c.user.id = :userId")
    Optional<Cart> findByUserIdWithItems(Long userId);
    
    @Query("SELECT c FROM Cart c JOIN FETCH c.user WHERE c.user.username = :username")
    Optional<Cart> findByUsernameNoFetch(String username);
    
    Optional<Cart> findByUser(User user);
}
