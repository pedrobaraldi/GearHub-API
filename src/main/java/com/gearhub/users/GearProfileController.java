package com.gearhub.users;

import com.gearhub.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Setup e jogos", description = "Periféricos, hardware e jogos favoritos exibidos no perfil")
public class GearProfileController {

    private final GearProfileService service;
    private final UserProgressService progress;

    public GearProfileController(GearProfileService service, UserProgressService progress) {
        this.service = service;
        this.progress = progress;
    }

    @GetMapping("/users/{username}/progress")
    @Operation(summary = "Mostra nível, XP e de onde veio a XP de um perfil")
    public UserProgressService.Progress progress(@PathVariable String username) {
        return progress.progress(username);
    }

    @GetMapping("/games")
    @Operation(summary = "Lista os jogos disponíveis")
    public List<GearProfileService.Game> games() {
        return service.games();
    }

    @GetMapping("/games/{slug}")
    @Operation(summary = "Lista os campos de configuração de um jogo")
    public GearProfileService.GameDefinition game(@PathVariable String slug) {
        return service.game(slug);
    }

    @GetMapping("/gear-options")
    @Operation(summary = "Busca equipamentos de uma categoria, com as reviews mais populares primeiro")
    public List<GearProfileService.GearOption> gearOptions(@RequestParam String category,
            @RequestParam(required = false) String q) {
        return service.gearOptions(category, q);
    }

    @GetMapping("/users/{username}/games")
    @Operation(summary = "Lista os jogos favoritos de um perfil")
    public List<GearProfileService.GameConfig> userGames(@PathVariable String username) {
        return service.userGames(username);
    }

    @GetMapping("/users/{username}/setup")
    @Operation(summary = "Lista os itens preenchidos do setup de um perfil")
    public List<GearProfileService.SetupItem> publicSetup(@PathVariable String username) {
        return service.publicSetup(username);
    }

    @GetMapping("/users/{username}/setup/photos")
    @Operation(summary = "Lista as fotos do setup de um perfil")
    public List<String> setupPhotos(@PathVariable String username) {
        return service.setupPhotos(username);
    }

    @PutMapping("/me/setup/photos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Salva as fotos do próprio setup (até 12)", security = @SecurityRequirement(name = "session"))
    public void saveSetupPhotos(@AuthenticationPrincipal CurrentUser current,
            @RequestBody GearProfileService.SetupPhotos request) {
        service.saveSetupPhotos(current.id(), request);
    }

    @GetMapping("/me/setup")
    @Operation(summary = "Lista todos os campos do próprio setup", security = @SecurityRequirement(name = "session"))
    public List<GearProfileService.SetupItem> mySetup(@AuthenticationPrincipal CurrentUser current) {
        return service.mySetup(current.id());
    }

    @PutMapping("/me/setup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Salva o próprio setup", security = @SecurityRequirement(name = "session"))
    public void saveSetup(@AuthenticationPrincipal CurrentUser current, @RequestBody Map<String, String> values) {
        service.saveSetup(current.id(), values);
    }

    @PutMapping("/me/games/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Adiciona ou atualiza um jogo favorito (até 3)", security = @SecurityRequirement(name = "session"))
    public void saveGame(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable String slug,
            @RequestBody GearProfileService.SaveGame request) {
        service.saveGame(current.id(), slug, request);
    }

    @DeleteMapping("/me/games/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove um jogo favorito", security = @SecurityRequirement(name = "session"))
    public void removeGame(@AuthenticationPrincipal CurrentUser current, @PathVariable String slug) {
        service.removeGame(current.id(), slug);
    }
}
