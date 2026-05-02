package com.kb.account.service;

import com.kb.account.dto.PersonDTO;
import com.kb.account.model.Person;
import com.kb.account.model.User;
import com.kb.account.repository.PersonRepository;
import com.kb.account.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PersonService {
    private final PersonRepository personRepository;
    private final UserRepository userRepository;

    public PersonDTO createPerson(PersonDTO personDTO) {
        User user = userRepository.findById(personDTO.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Person person = new Person();
        person.setName(personDTO.getName());
        person.setUser(user);
        
        Person savedPerson = personRepository.save(person);
        return convertToDTO(savedPerson);
    }

    public PersonDTO getPersonById(Long id) {
        return personRepository.findById(id)
                .map(this::convertToDTO)
                .orElseThrow(() -> new RuntimeException("Person not found"));
    }

    public List<PersonDTO> getAllPersons() {
        return personRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<PersonDTO> getPersonsByUserId(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        return personRepository.findByUser(user).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public PersonDTO updatePerson(Long id, PersonDTO personDTO) {
        Person person = personRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Person not found"));
        
        if (personDTO.getName() != null) person.setName(personDTO.getName());
        
        if (personDTO.getUserId() != null) {
            User user = userRepository.findById(personDTO.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            person.setUser(user);
        }
        
        Person updatedPerson = personRepository.save(person);
        return convertToDTO(updatedPerson);
    }

    public void deletePerson(Long id) {
        personRepository.deleteById(id);
    }

    private PersonDTO convertToDTO(Person person) {
        return PersonDTO.builder()
                .id(person.getId())
                .name(person.getName())
                .userId(person.getUser() != null ? person.getUser().getId() : null)
                .build();
    }
}
