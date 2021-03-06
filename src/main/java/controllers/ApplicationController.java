package controllers;

import java.util.List;

import models.Game;
import models.Playday;
import models.User;
import models.enums.Constants;
import ninja.Context;
import ninja.Result;
import ninja.Results;
import services.CalculationService;
import services.DataService;
import services.I18nService;

import com.google.inject.Inject;

/**
 * 
 * @author svenkubiak
 *
 */
public class ApplicationController extends RootController {

    @Inject
    private DataService dataService;
    
    @Inject
    private CalculationService calculationService;
    
    @Inject
    private I18nService i18nService;

    public Result index(Context context) {
        final int pointsDiff = calculationService.getPointsToFirstPlace(context.getAttribute(Constants.CONNECTEDUSER.asString(), User.class));
        final String diffToTop = i18nService.getDiffToTop(pointsDiff);
        final Playday playday = dataService.findCurrentPlayday();
        final List<User> topUsers = dataService.findTopThreeUsers();
        final List<Game> games = dataService.findGamesByPlayday(playday);
        final long users = dataService.countAll(User.class);
        
        dataService.findAllGamesWithNoResult();
        
        return Results.html()
                .render("topUsers", topUsers)
                .render("playday", playday)
                .render("games", games)
                .render("users", users)
                .render("diffToTop", diffToTop);
    }
}