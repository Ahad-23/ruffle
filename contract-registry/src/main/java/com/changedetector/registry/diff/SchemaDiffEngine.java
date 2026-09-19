package com.changedetector.registry.diff;

import com.changedetector.registry.entity.*;
import com.changedetector.registry.repository.EndpointRepository;
import com.changedetector.registry.repository.SchemaChangeRepository;
import com.changedetector.registry.repository.SchemaFieldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SchemaDiffEngine {

    private static final Logger log = LoggerFactory.getLogger(SchemaDiffEngine.class);

    private final EndpointRepository endpointRepository;
    private final SchemaFieldRepository schemaFieldRepository;
    private final SchemaChangeRepository schemaChangeRepository;

    public SchemaDiffEngine(EndpointRepository endpointRepository,
                            SchemaFieldRepository schemaFieldRepository,
                            SchemaChangeRepository schemaChangeRepository) {
        this.endpointRepository = endpointRepository;
        this.schemaFieldRepository = schemaFieldRepository;
        this.schemaChangeRepository = schemaChangeRepository;
    }

    @Transactional
    public List<SchemaChange> computeAndSaveDiff(com.changedetector.registry.entity.Service service,
                                                 SpecVersion oldVersion,
                                                 SpecVersion newVersion) {
        List<SchemaChange> changes = computeDiff(service, oldVersion, newVersion);
        if (!changes.isEmpty()) {
            return schemaChangeRepository.saveAll(changes);
        }
        return Collections.emptyList();
    }

    public List<SchemaChange> computeDiff(com.changedetector.registry.entity.Service service,
                                          SpecVersion oldVersion,
                                          SpecVersion newVersion) {
        List<SchemaChange> changes = new ArrayList<>();

        List<Endpoint> oldEndpoints = endpointRepository.findBySpecVersionId(oldVersion.getId());
        List<Endpoint> newEndpoints = endpointRepository.findBySpecVersionId(newVersion.getId());

        Map<String, Endpoint> oldEpMap = oldEndpoints.stream()
                .collect(Collectors.toMap(e -> e.getHttpMethod().toUpperCase() + " " + e.getPath(), e -> e, (a, b) -> a));
        Map<String, Endpoint> newEpMap = newEndpoints.stream()
                .collect(Collectors.toMap(e -> e.getHttpMethod().toUpperCase() + " " + e.getPath(), e -> e, (a, b) -> a));

        // 1. Check for removed endpoints
        for (Map.Entry<String, Endpoint> entry : oldEpMap.entrySet()) {
            String key = entry.getKey();
            Endpoint oldEp = entry.getValue();
            if (!newEpMap.containsKey(key)) {
                changes.add(new SchemaChange(
                        service, oldVersion, newVersion,
                        "ENDPOINT_REMOVED",
                        oldEp.getPath(),
                        oldEp.getHttpMethod(),
                        null,
                        oldEp.getPath(),
                        null,
                        "BREAKING"
                ));
            }
        }

        // 2. Check for field-level differences for common endpoints
        for (Map.Entry<String, Endpoint> entry : oldEpMap.entrySet()) {
            String key = entry.getKey();
            Endpoint oldEp = entry.getValue();
            Endpoint newEp = newEpMap.get(key);

            if (newEp != null) {
                List<SchemaField> oldFields = schemaFieldRepository.findByEndpointId(oldEp.getId());
                List<SchemaField> newFields = schemaFieldRepository.findByEndpointId(newEp.getId());

                diffFieldsForEndpoint(service, oldVersion, newVersion, oldEp, oldFields, newFields, changes);
            }
        }

        return changes;
    }

    private void diffFieldsForEndpoint(com.changedetector.registry.entity.Service service,
                                       SpecVersion oldVersion,
                                       SpecVersion newVersion,
                                       Endpoint endpoint,
                                       List<SchemaField> oldFields,
                                       List<SchemaField> newFields,
                                       List<SchemaChange> changes) {

        Map<String, SchemaField> oldFieldMap = oldFields.stream()
                .collect(Collectors.toMap(SchemaField::getFieldPath, f -> f, (a, b) -> a));
        Map<String, SchemaField> newFieldMap = newFields.stream()
                .collect(Collectors.toMap(SchemaField::getFieldPath, f -> f, (a, b) -> a));

        List<SchemaField> removedFields = new ArrayList<>();
        List<SchemaField> addedFields = new ArrayList<>();

        // Check for modifications and removals
        for (Map.Entry<String, SchemaField> entry : oldFieldMap.entrySet()) {
            String path = entry.getKey();
            SchemaField oldF = entry.getValue();
            SchemaField newF = newFieldMap.get(path);

            if (newF == null) {
                removedFields.add(oldF);
            } else {
                // Check Type Change
                if (!Objects.equals(oldF.getFieldType(), newF.getFieldType())) {
                    changes.add(new SchemaChange(
                            service, oldVersion, newVersion,
                            "TYPE_CHANGED",
                            endpoint.getPath(),
                            endpoint.getHttpMethod(),
                            path,
                            oldF.getFieldType(),
                            newF.getFieldType(),
                            "BREAKING"
                    ));
                }
                // Check Required Change
                boolean oldReq = Boolean.TRUE.equals(oldF.getIsRequired());
                boolean newReq = Boolean.TRUE.equals(newF.getIsRequired());
                if (!oldReq && newReq) {
                    changes.add(new SchemaChange(
                            service, oldVersion, newVersion,
                            "REQUIRED_ADDED",
                            endpoint.getPath(),
                            endpoint.getHttpMethod(),
                            path,
                            "optional",
                            "required",
                            "WARNING"
                    ));
                }
            }
        }

        // Check for added fields
        for (Map.Entry<String, SchemaField> entry : newFieldMap.entrySet()) {
            String path = entry.getKey();
            SchemaField newF = entry.getValue();
            if (!oldFieldMap.containsKey(path)) {
                addedFields.add(newF);
            }
        }

        // Heuristic: Check if any removed + added fields form a FIELD_RENAMED pair
        // (same parent schema, same field type, and similar name e.g. camelCase vs snake_case or typo fix)
        Set<String> matchedRemovedPaths = new HashSet<>();
        Set<String> matchedAddedPaths = new HashSet<>();

        for (SchemaField rem : removedFields) {
            for (SchemaField add : addedFields) {
                if (!matchedAddedPaths.contains(add.getFieldPath()) &&
                        Objects.equals(rem.getParentSchema(), add.getParentSchema()) &&
                        Objects.equals(rem.getFieldType(), add.getFieldType()) &&
                        isRenameCandidate(rem.getFieldPath(), add.getFieldPath())) {
                    // Match as renamed
                    matchedRemovedPaths.add(rem.getFieldPath());
                    matchedAddedPaths.add(add.getFieldPath());
                    changes.add(new SchemaChange(
                            service, oldVersion, newVersion,
                            "FIELD_RENAMED",
                            endpoint.getPath(),
                            endpoint.getHttpMethod(),
                            rem.getFieldPath(),
                            rem.getFieldPath(),
                            add.getFieldPath(),
                            "BREAKING"
                    ));
                    break;
                }
            }
        }

        // Process remaining removed fields
        for (SchemaField rem : removedFields) {
            if (!matchedRemovedPaths.contains(rem.getFieldPath())) {
                changes.add(new SchemaChange(
                        service, oldVersion, newVersion,
                        "FIELD_REMOVED",
                        endpoint.getPath(),
                        endpoint.getHttpMethod(),
                        rem.getFieldPath(),
                        rem.getFieldType(),
                        null,
                        "BREAKING"
                ));
            }
        }

        // Process remaining added fields
        for (SchemaField add : addedFields) {
            if (!matchedAddedPaths.contains(add.getFieldPath())) {
                boolean isReq = Boolean.TRUE.equals(add.getIsRequired());
                changes.add(new SchemaChange(
                        service, oldVersion, newVersion,
                        isReq ? "REQUIRED_FIELD_ADDED" : "OPTIONAL_FIELD_ADDED",
                        endpoint.getPath(),
                        endpoint.getHttpMethod(),
                        add.getFieldPath(),
                        null,
                        add.getFieldType(),
                        isReq ? "WARNING" : "INFO"
                ));
            }
        }
    }

    private boolean isRenameCandidate(String oldName, String newName) {
        if (oldName == null || newName == null) return false;
        String name1 = oldName.contains(".") ? oldName.substring(oldName.lastIndexOf('.') + 1) : oldName;
        String name2 = newName.contains(".") ? newName.substring(newName.lastIndexOf('.') + 1) : newName;

        String s1 = name1.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        String s2 = name2.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        if (s1.equals(s2)) return true;
        if ((s1.length() >= 4 && s2.contains(s1)) || (s2.length() >= 4 && s1.contains(s2))) return true;
        return levenshteinDistance(s1, s2) <= Math.max(2, Math.min(s1.length(), s2.length()) / 3);
    }

    private int levenshteinDistance(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) costs[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }
}
