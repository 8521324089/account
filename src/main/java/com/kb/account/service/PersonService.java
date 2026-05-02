package com.kb.account.service;

import com.kb.account.dto.PersonDTO;
import com.kb.account.exception.ResourceNotFoundException;
import com.kb.account.model.Person;
import com.kb.account.model.User;
import com.kb.account.repository.PersonRepository;
import com.kb.account.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonService {
    private final PersonRepository personRepository;
    private final UserRepository userRepository;

    @Transactional
    public PersonDTO createPerson(PersonDTO personDTO) {
        User user = userRepository.findById(personDTO.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", personDTO.getUserId()));

        Person person = new Person();
        person.setName(personDTO.getName());
        person.setPhone(personDTO.getPhone());
        person.setUser(user);

        Person saved = personRepository.save(person);
        log.info("Person created: id={}, userId={}", saved.getId(), user.getId());
        return convertToDTO(saved);
    }

    @Transactional(readOnly = true)
    public PersonDTO getPersonById(Long id) {
        return personRepository.findById(id)
                .map(this::convertToDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Person", id));
    }

    @Transactional(readOnly = true)
    public List<PersonDTO> getPersonsByUserId(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return personRepository.findByUser(user).stream()
                .map(this::convertToDTO).collect(Collectors.toList());
    }

    @Transactional
    public PersonDTO updatePerson(Long id, PersonDTO personDTO) {
        Person person = personRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Person", id));
        if (personDTO.getName() != null) person.setName(personDTO.getName());
        if (personDTO.getPhone() != null) person.setPhone(personDTO.getPhone());
        Person updated = personRepository.save(person);
        log.info("Person updated: id={}", updated.getId());
        return convertToDTO(updated);
    }

    @Transactional
    public void deletePerson(Long id) {
        if (!personRepository.existsById(id)) {
            throw new ResourceNotFoundException("Person", id);
        }
        personRepository.deleteById(id);
        log.info("Person deleted: id={}", id);
    }

    private PersonDTO convertToDTO(Person person) {
        return PersonDTO.builder()
                .id(person.getId())
                .name(person.getName())
                .phone(person.getPhone())
                .userId(person.getUser() != null ? person.getUser().getId() : null)
                .build();
    }
}
