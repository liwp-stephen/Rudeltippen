package controllers;

import interfaces.AppConstants;
import interfaces.CheckAccess;

import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.List;

import models.Confirmation;
import models.ConfirmationType;
import models.Settings;
import models.User;

import org.apache.commons.lang.StringUtils;

import play.Logger;
import play.Play;
import play.data.validation.Validation;
import play.db.jpa.Transactional;
import play.i18n.Messages;
import play.libs.Codec;
import play.libs.Crypto;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;
import play.utils.Java;
import services.MailService;
import services.TwitterService;
import utils.AppUtils;
import utils.ValidationUtils;
import utils.ViewUtils;

public class Auth extends Controller implements AppConstants{
	@Before(unless={"login", "authenticate", "logout", "forgotten", "resend", "register", "create", "confirm"})
    protected static void checkAccess() throws Throwable {
		AppUtils.setAppLanguage();

    	if (!session.contains("username")) {
            flash.put("url", "/");
            login();
        }

    	CheckAccess check = getActionAnnotation(CheckAccess.class);
    	if (check != null) {
            check(check);
    	}

    	check = getControllerInheritedAnnotation(CheckAccess.class);
        if (check != null) {
            check(check);
        }
    }

	@Before
	protected static void registration() {
		final Settings settings = AppUtils.getSettings();
		if (settings == null) {
			renderArgs.put("isEnableRegistration", false);
		} else {
			renderArgs.put("isEnableRegistration", settings.isEnableRegistration());
		}
		renderArgs.put("theme", ViewUtils.getTheme());
	}

    private static void check(CheckAccess check) throws Throwable {
        for (String profile : check.value()) {
            final boolean hasProfile = (Boolean) Security.invoke("check", profile);
            if (!hasProfile) {
                Security.invoke("onCheckFailed", profile);
            }
        }
    }

	public static void confirm(String token) throws Throwable {
		Confirmation confirmation = null;
		validation.required(token);
		validation.match(token, CONFIRMATIONPATTERN);

		if (validation.hasErrors()) {
			flash.put("warningmessage", Messages.get("controller.users.invalidtoken"));
		} else {
			confirmation = Confirmation.find("byToken", token).first();
		}

		if (confirmation != null && !validation.hasErrors()) {
			User user = confirmation.getUser();
			if (user != null) {
				ConfirmationType confirmationType = confirmation.getConfirmType();
				doConfirmation(confirmation, user, confirmationType);
			} else {
				flash.put("warningmessage", Messages.get("controller.users.invalidtoken"));
			}
		} else {
			flash.put("warningmessage", Messages.get("controller.users.invalidtoken"));
		}
		flash.keep();
		redirect("/auth/login");
	}

	private static void doConfirmation(Confirmation confirmation, User user, ConfirmationType confirmationType) {
		if ((ConfirmationType.ACTIVATION).equals(confirmationType)) {
			activateAndSetAvatar(user);
			Logger.info("User activated: " + user.getUsername());
			flash.put("infomessage", Messages.get("controller.users.accountactivated"));
			TwitterService.updateStatus(user.getNickname() + " " + Messages.get("controller.users.twitter"));
		} else if ((ConfirmationType.CHANGEUSERNAME).equals(confirmationType)) {
			final String oldusername = user.getUsername();
			final String newusername = Crypto.decryptAES(confirmation.getConfirmValue());
			user.setUsername(newusername);
			user._save();
			session.remove("username");
			Logger.info("User changed username... old username: " + oldusername + " - " + "new username: " + newusername);
			flash.put("infomessage", Messages.get("controller.users.changedusername"));
		} else if ((ConfirmationType.CHANGEUSERPASS).equals(confirmationType)) {
			user.setUserpass(Crypto.decryptAES(confirmation.getConfirmValue()));
			user._save();
			session.remove("username");
			Logger.info(user.getUsername() + " changed his password");
			flash.put("infomessage", Messages.get("controller.users.changeduserpass"));
		} else if ((ConfirmationType.FORGOTUSERPASS).equals(confirmationType)) {
			final String userpass = AppUtils.generatePassword(12);
			user.setUserpass(AppUtils.hashPassword(userpass, user.getSalt()));
			user._save();
			session.remove("username");
			MailService.newuserpass(user, userpass);
			Logger.info("New password was send to: " + user.getUsername());
			flash.put("infomessage", Messages.get("controller.users.forgotuserpass"));
		}
		confirmation._delete();
	}

	private static void activateAndSetAvatar(User user) {
		String avatar = AppUtils.getGravatarImage(user.getUsername(), "retro", 128);
		String avatarSmall = AppUtils.getGravatarImage(user.getUsername(), "retro", 64);
		if (StringUtils.isNotBlank(avatar)) {
			user.setPictureLarge(avatar);
		}
		if (StringUtils.isNotBlank(avatarSmall)) {
			user.setPicture(avatarSmall);
		}
		
		user.setActive(true);
		user._save();
	}

	@Transactional(readOnly=true)
    public static void register() {
    	final Settings settings = AppUtils.getSettings();
    	if (!settings.isEnableRegistration()) {
    		redirect("/");
    	}

    	render();
    }

    public static void create(String nickname, String username, String usernameConfirmation, String userpass, String userpassConfirmation) {
    	if (AppUtils.verifyAuthenticity()) { checkAuthenticity(); }

    	final Settings settings = AppUtils.getSettings();
    	if (!settings.isEnableRegistration()) {
    		redirect("/");
    	}

		validation.required(username);
		validation.required(userpass);
		validation.required(nickname);
		validation.email(username);
		validation.equals(username, usernameConfirmation);
		validation.equals(userpass, userpassConfirmation);
		validation.minSize(userpass, 8);
		validation.maxSize(userpass, 32);
		validation.minSize(nickname, 3);
		validation.maxSize(nickname, 20);
		validation.isTrue(ValidationUtils.isValidNickname(nickname)).key("nickname").message(Messages.get("controller.users.invalidnickname"));
		validation.isTrue(!ValidationUtils.nicknameExists(nickname)).key("nickname").message(Messages.get("controller.users.nicknamexists"));
		validation.isTrue(!ValidationUtils.usernameExists(username)).key("username").message(Messages.get("controller.users.emailexists"));

		if (validation.hasErrors()) {
			params.flash();
			validation.keep();
			register();
		} else {
			String salt = Codec.hexSHA1(Codec.UUID());
		    User user = new User();
			user.setRegistered(new Date());
			user.setNickname(nickname);
			user.setUsername(username);
			user.setActive(false);
			user.setReminder(true);
			user.setAdmin(false);
			user.setSalt(salt);
			user.setUserpass(AppUtils.hashPassword(userpass, salt));
			user.setPoints(0);
			user._save();

			final String token = Codec.UUID();
			ConfirmationType confirmType = ConfirmationType.ACTIVATION;
			Confirmation confirmation = new Confirmation();
			confirmation.setConfirmType(confirmType);
			confirmation.setConfirmValue(Crypto.encryptAES(Codec.UUID()));
			confirmation.setCreated(new Date());
			confirmation.setToken(token);
			confirmation.setUser(user);
			confirmation._save();

			MailService.confirm(user, token, confirmType);
			if (settings.isInformOnNewTipper()) {
				List<User> admins = User.find("byAdmin", true).fetch();
				for (User admin : admins) {
					MailService.newuser(user, admin);
				}
			}
			Logger.info("User registered: " + user.getUsername());
		}
		render(settings);
	}

    public static void login() {
    	if (session.contains("username")) {
    		redirect("/application/index");
    	}

        final Http.Cookie remember = request.cookies.get("rememberme");
        if (remember != null && remember.value.indexOf("-") > 0) {
            final String sign = remember.value.substring(0, remember.value.indexOf("-"));
            final String username = remember.value.substring(remember.value.indexOf("-") + 1);
            if (sign != null && username != null && Crypto.sign(username).equals(sign)) {
                session.put("username", username);
                redirectToOriginalURL();
            }
        }
        flash.keep("url");
        render();
    }

    public static void resend(String username) throws Throwable {
    	if (AppUtils.verifyAuthenticity()) { checkAuthenticity(); }

    	validation.required(username);
		validation.isTrue(ValidationUtils.usernameExists(username)).key("username").message("validation.userNotExists");
		validation.email(username);

		if (validation.hasErrors()) {
    		flash.put("errormessage", Messages.get("controller.auth.resenderror"));
    		validation.keep();
    		params.flash();
    		forgotten();
		} else {
        	User user = User.find("byUsername", username).first();
        	if (user != null) {
        		final String token = Codec.UUID();
        		ConfirmationType confirmType = ConfirmationType.FORGOTUSERPASS;
        		Confirmation confirmation = new Confirmation();
        		confirmation.setUser(user);
        		confirmation.setToken(token);
        		confirmation.setConfirmType(confirmType);
        		confirmation.setConfirmValue(Crypto.encryptAES(Codec.UUID()));
        		confirmation.setCreated(new Date());
        		confirmation._save();

        		MailService.confirm(user, token, confirmType);
        		flash.put("infomessage", Messages.get("confirm.message"));
        		login();
        	}
		}
		flash.keep();
		redirect("/");
    }

    @Transactional(readOnly=true)
    public static void forgotten() {
    	render();
    }

    public static void authenticate(String username, String userpass, boolean remember) {
    	if (AppUtils.verifyAuthenticity()) { checkAuthenticity(); }

        Boolean allowed = false;
        try {
            allowed = (Boolean) Security.invoke("authenticate", username, userpass);
            validation.isTrue(allowed);
            validation.required(username);
            validation.required(userpass);
            validation.email(username);
        } catch (UnsupportedOperationException e) {
        	Logger.error("UnsupportedOperationException while authenticating", e);
        } catch (Throwable e) {
            Logger.error("Authentication exception", e);
        }

        if (!allowed || validation.hasErrors()) {
            flash.keep("url");
            flash.put("errormessage", Messages.get("validation.invalidLogin"));
            params.flash();
            Validation.keep();
            login();
        } else {
        	session.put("username", username);
            if (remember) {
                response.setCookie("rememberme", Crypto.sign(username) + "-" + username, "7d");
            }
        }

        redirectToOriginalURL();
    }

    @Transactional(readOnly=true)
    public static void logout() throws Throwable {
        Security.invoke("onDisconnected");
    	session.clear();
        response.removeCookie("rememberme");

        flash.put("infomessage", Messages.get("controller.auth.logout"));
        flash.keep();

        login();
    }

    static void redirectToOriginalURL() {
        try {
            Security.invoke("onAuthenticated");
        } catch (Throwable e) {
             Logger.error("Failed to onvoke onAuthenticated", e);
        }
        String url = flash.get("url");

        if (StringUtils.isBlank(url)) {
            url = "/";
        }

        redirect(url);
    }

    public static class Security extends Controller {
        static boolean authenticate(String username, String userpass) {
        	String usersalt = null;
        	User user = User.find("byUsername", username).first();
        	if (user != null) {
        		usersalt = user.getSalt();
        		return User.connect(username, AppUtils.hashPassword(userpass, usersalt)) != null;
        	}

        	return false;
        }

        static boolean check(String profile) {
            User user = User.find("byUsername", connected()).first();
            if (user == null) {
                return false;
            }

            return user.isAdmin();
        }

        public static String connected() {
            return session.get("username");
        }

        static boolean isConnected() {
            return session.contains("username");
        }

        static void onAuthenticated() {
            Logger.info("User logged in: " + Security.connected());
        }

        static void onDisconnected() {
        	Logger.info("User logged out: " + Security.connected());
        }

        static void onCheckFailed(String profile) {
            forbidden();
        }

        private static Object invoke(String m, Object... args) throws Throwable {
            Class security = null;
            List<Class> classes = Play.classloader.getAssignableClasses(Security.class);
            if (classes.size() == 0) {
                security = Security.class;
            } else {
                security = classes.get(0);
            }
            try {
                return Java.invokeStaticOrParent(security, m, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }
}