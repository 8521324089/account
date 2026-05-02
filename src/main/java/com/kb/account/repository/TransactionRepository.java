package com.kb.account.repository;

import com.kb.account.model.Transaction;
import com.kb.account.model.User;
import com.kb.account.model.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findByUser(User user);
    List<Transaction> findByPerson(Person person);
    List<Transaction> findByUserAndType(User user, String type);
}
