package com.crm.travelcrm.trash;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.trash.dto.TrashGroupDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Trash (Recycle Bin) API a future sidebar UI will consume. Every action is
 * permission-gated and tenant-scoped; records are addressed by {@code publicId} only.
 *
 * <ul>
 *   <li>{@code GET  /api/trash}                         — list trashed records grouped by module</li>
 *   <li>{@code POST /api/trash/{type}/{publicId}/restore} — restore a record</li>
 *   <li>{@code DELETE /api/trash/{type}/{publicId}}      — permanently delete now (most restricted)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/trash")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TRASH_VIEW')")   // class default; mutating methods override below
public class TrashController {

    private final TrashService trashService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TrashGroupDto>>> listTrash() {
        return ResponseEntity.ok(
                ApiResponse.success("Trash fetched successfully", trashService.listTrash()));
    }

    @PostMapping("/{type}/{publicId}/restore")
    @PreAuthorize("hasAuthority('TRASH_RESTORE')")
    public ResponseEntity<ApiResponse<Void>> restore(
            @PathVariable String type, @PathVariable UUID publicId) {
        trashService.restore(type, publicId);
        return ResponseEntity.ok(ApiResponse.success("Record restored successfully"));
    }

    @DeleteMapping("/{type}/{publicId}")
    @PreAuthorize("hasAuthority('TRASH_DELETE')")
    public ResponseEntity<ApiResponse<Void>> deleteNow(
            @PathVariable String type, @PathVariable UUID publicId) {
        trashService.deleteNow(type, publicId);
        return ResponseEntity.ok(ApiResponse.success("Record permanently deleted"));
    }
}