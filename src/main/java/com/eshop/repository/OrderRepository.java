package com.eshop.repository;

import com.eshop.entity.Order;
import com.eshop.entity.User;
import com.eshop.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("SELECT o FROM Order o JOIN FETCH o.items i JOIN FETCH i.articles WHERE o.user = :user")
    List<Order> findByUserWithItems(User user);
    
    @Query("SELECT o FROM Order o JOIN FETCH o.items i JOIN FETCH i.articles WHERE o.user.username = :username")
    List<Order> findByUsernameWithItems(String username);
    
    @Query("SELECT o FROM Order o JOIN FETCH o.items i JOIN FETCH i.articles WHERE o.user.id = :userId")
    List<Order> findByUserIdWithItems(Long userId);
    
    @Query("SELECT o FROM Order o JOIN FETCH o.items i JOIN FETCH i.articles WHERE o.user.id = :userId")
    Page<Order> findByUserIdWithPageable(@Param("userId") Long userId, Pageable pageable);
    
    @Query("SELECT o FROM Order o JOIN FETCH o.items i JOIN FETCH i.articles WHERE o.user.id = :userId AND o.status = :status")
    Page<Order> findByUserIdAndStatus(@Param("userId") Long userId, @Param("status") OrderStatus status, Pageable pageable);
    
    List<Order> findByUser(User user);
    
    List<Order> findByStatus(OrderStatus status);
    
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
    
    Page<Order> findByStatusContainingIgnoreCase(String status, Pageable pageable);
    
    @Query("SELECT o FROM Order o JOIN FETCH o.items i JOIN FETCH i.articles WHERE (:status IS NULL OR o.status = :status)")
    Page<Order> findByStatusWithPageable(@Param("status") OrderStatus status, Pageable pageable);
    
    @Query("SELECT o FROM Order o JOIN FETCH o.items i JOIN FETCH i.articles WHERE (:status IS NULL OR o.status = :status) AND (LOWER(o.user.username) LIKE LOWER(CONCAT('%', :search, '%')) OR CAST(o.id AS string) LIKE CONCAT('%', :search, '%'))")
    Page<Order> findByStatusAndSearch(@Param("status") OrderStatus status, @Param("search") String search, Pageable pageable);
    
    @Query("SELECT o FROM Order o JOIN FETCH o.items i JOIN FETCH i.articles WHERE o.id = :id")
    Optional<Order> findByIdWithItems(Long id);
}
