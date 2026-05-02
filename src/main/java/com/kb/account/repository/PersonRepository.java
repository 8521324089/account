package com.kb.account.repository;

import com.kb.account.model.Person;
import com.kb.account.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PersonRepository extends JpaRepository<Person, Long> {
    List<Person> findByUser(User user);
}
