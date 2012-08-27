package project.riley.datageneration;

/** Generate .arff file of (uid, link_id, like/dislike, vec(features)) from App data
 * 
 * @author Riley Kidd
 * @author Scott Sanner
 * 
 * Note: Currently ignoring posting a link as evidence of liking, but probably accounts
 *       for a negligible fraction of the data.
 */


import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Map.Entry;

import org.nicta.lr.util.*;

import project.ifilter.messagefrequency.PredictiveWords;

public class DataGeneratorPassiveActive {

	public static final String YES = "'y'".intern();
	public static final String NO  = "'n'".intern();
	public static final String PRE = ",".intern();
	public static final String[] RATINGS = new String[]{ YES, NO };

	public static final int LINK_FREQ_THRESHOLD = 1;

	public static boolean SKIP_NEGATIVES = false;
	public static double  NEGATIVE_SKIP_PERCENT = 0.98d;

	/*
	 * For caching data
	 */
	public static ArrayList<EInteractionType> _featuresInt = null;
	public static ArrayList<EDirectionType>   _featuresDir = null;
	public static Map<Long,Set<Long>> _uid2all_passive_linkids_likes = null;
	public static Map<Long,Set<Long>> _uid2linkids_likes = null;
	public static Map<Long,Set<Long>> _uid2linkids_dislikes = null;
	public static Map<EInteractionType,Map<EDirectionType,Map<Long,Set<Long>>>> _int_dir2uid_linkid = null;
	public static ArrayList<String> topNWords;

	public static Set<Long> usersSeen = new HashSet<Long>();
	public static Set<Long> linksSeen = new HashSet<Long>();

	/*
	 * Generate data for accurately labeled data from NICTA app
	 */
	public static PrintWriter _writer;

	public static void populateCachedData(boolean active_likes) throws Exception {

		topNWords = PredictiveWords.getTopN(5);
		// For all uids in the DB, get their set of LINK likes 
		_uid2all_passive_linkids_likes = UserUtil.getLikes(ELikeType.LINK);

		if (active_likes) {

			SKIP_NEGATIVES = false;

			////////////////////////////////////////////////////////////////////////
			// Get dataset of likes / dislikes for active data
			////////////////////////////////////////////////////////////////////////

			_uid2linkids_likes = new HashMap<Long,Set<Long>>();
			_uid2linkids_dislikes = new HashMap<Long,Set<Long>>();

			String userQuery = "select uid, link_id, rating from trackRecommendedLinks where rating != 0;"; // 0 = not rated
			Statement statement = SQLUtil.getStatement();

			ResultSet result = statement.executeQuery(userQuery);
			while (result.next()) {
				long uid = result.getLong(1);
				long link_id = result.getLong(2);
				// 0 = not rated, 1 = liked, 2 = not liked
				int rating = result.getInt(3);

				// SPS - cannot do this yet... 0 should be thrown out first
				// userLikes.put(link_id, (rating == 1 ? "y" : "n"));
				if (rating < 1 || rating > 2)
					continue;
				boolean is_liked = (rating == 1);

				Set<Long> userLikes = 
						(is_liked ? _uid2linkids_likes : _uid2linkids_dislikes).get(uid);
				if (userLikes == null){
					userLikes = new HashSet<Long>();
					(is_liked ? _uid2linkids_likes : _uid2linkids_dislikes).put(uid, userLikes);
				}
				userLikes.add(link_id);
				linksSeen.add(link_id);
			}
			result.close();
			statement.close();

		} else { // !active

			////////////////////////////////////////////////////////////////////////
			// Get dataset of likes / dislikes for passive data
			////////////////////////////////////////////////////////////////////////

			SKIP_NEGATIVES = true; // too many negatives, take one out of every NEGATIVE_SKIP_RATE

			//_uid2linkids_likes = _uid2all_passive_linkids_likes;
			_uid2linkids_likes = new HashMap<Long,Set<Long>>();
			_uid2linkids_dislikes = new HashMap<Long,Set<Long>>();
			Set<Long> app_user_uids = UserUtil.getAppUserIds();

			// Get some popular and app user links
			//HashSet<Long> popular_links = new HashSet<Long>(getLinksSortedByPopularity(LINK_FREQ_THRESHOLD));
			//HashSet<Long> app_user_links = getUnionOfLikedLinks(app_user_uids);
			//System.out.println("Popular link set size (> " + LINK_FREQ_THRESHOLD + " likes): " + popular_links.size());
			//System.out.println("App user link likes size: " + app_user_links.size());

			HashSet<Long> popular_app_user_links = new HashSet<Long>(getAppUserLinksSortedByPopularity(LINK_FREQ_THRESHOLD)); //new HashSet<Long>();
			//popular_app_user_links.addAll(popular_links);
			//popular_app_user_links.retainAll(app_user_links);			
			System.out.println("Popular App user link likes size: " + popular_app_user_links.size());

			for (Long uid : app_user_uids) {
				Set<Long> links_liked = _uid2all_passive_linkids_likes.get(uid);
				System.out.println(ExtractRelTables.UID_2_NAME.get(uid) + " has liked #links = " + (links_liked == null ? 0 : links_liked.size()));

				Set<Long> links_liked_to_set = _uid2linkids_likes.get(uid);
				Set<Long> links_disliked_to_set = _uid2linkids_dislikes.get(uid);
				if (links_liked_to_set == null) {
					links_liked_to_set = new HashSet<Long>();
					links_disliked_to_set = new HashSet<Long>();
					_uid2linkids_likes.put(uid, links_liked_to_set);
					_uid2linkids_dislikes.put(uid, links_disliked_to_set);					
				}				

				// If a user has not liked a link in popular_app_user_links, consider it a dislike,
				// otherwise a like.
				for (Long link_id : popular_app_user_links) {
					linksSeen.add(link_id);
					if (links_liked == null || !links_liked.contains(link_id))
						links_disliked_to_set.add(link_id);
					else
						links_liked_to_set.add(link_id);
				}

			}
		}

		////////////////////////////////////////////////////////////////////////
		// For every user and interaction type/dir, get set of items liked by 
		// that set of alters (threshold for now at 1)
		//
		// Later, this will make it fast to generate features with one link set 
		// containment check
		////////////////////////////////////////////////////////////////////////

		// A parallel list of all Interaction X Direction generated 
		_featuresInt = new ArrayList<EInteractionType>();
		_featuresDir = new ArrayList<EDirectionType>();

		// Interaction -> Direction -> UID -> Set<Link_IDs>
		_int_dir2uid_linkid = new HashMap<EInteractionType,Map<EDirectionType,Map<Long,Set<Long>>>>();
		for (EInteractionType itype : EInteractionType.values()) {

			// Don't need interactions beyond real
			if (itype == EInteractionType.REAL)
				break;

			Map<EDirectionType,Map<Long,Set<Long>>> dir2rest = _int_dir2uid_linkid.get(itype);
			if (dir2rest == null) {
				dir2rest = new HashMap<EDirectionType,Map<Long,Set<Long>>>();
				_int_dir2uid_linkid.put(itype, dir2rest);
			}

			for (EDirectionType dir : EDirectionType.values()) {

				if (dir == EDirectionType.BIDIR)
					continue;
				else if (itype == EInteractionType.FRIENDS && dir == EDirectionType.OUTGOING)
					continue;

				// Maintain feature list in parallel arrays of int/dir
				_featuresInt.add(itype);
				_featuresDir.add(dir);

				Map<Long,Set<Long>> uid2rest = dir2rest.get(dir);
				if (uid2rest == null) {
					uid2rest = new HashMap<Long,Set<Long>>();
					dir2rest.put(dir, uid2rest);
				}

				Interaction i = UserUtil.getUserInteractions(itype, dir);

				//System.out.println("**********************\n" + _featuresInt + "\n" + _featuresDir);
				//System.out.println("**********************\n" + itype + "_" + dir + " #alters = " + i.getAllInteractions().size());

				for (Long uid : _uid2linkids_likes.keySet()) {

					// ** Note: these are passive likes on Facebook!
					Set<Long> alters = i.getInteractions(uid);
					//System.out.println(ExtractRelTables.UID_2_NAME.get(uid) + " " + itype + "_" + dir + " #alters = " + (alters == null ? 0 : alters.size()));

					HashMap<Long,Integer> other_likes_id2count = ExtractRelTables.GetLikesInteractions(uid, i, _uid2all_passive_linkids_likes);
					//P(like | friend likes) = P(like and friend likes) / P(friend likes)
					//                       = F(like and friend likes) / F(friend likes)
					Set<Long> other_likes_ids = ExtractRelTables.ThresholdAtK(other_likes_id2count, /*k*/1);
					if (other_likes_ids == null)
						other_likes_ids = new HashSet<Long>();
					uid2rest.put(uid, other_likes_ids);

					//System.out.println("-- count of set of links liked by alters under uid,itype,dir: " + other_likes_ids.size());
				}
			}
		}

		for (long uid : UserUtil.getAppUserIds()){
			usersSeen.add(uid);
		}

		extractLinkFeatures();
	}

	public static HashSet<Long> getUnionOfLikedLinks(Set<Long> app_user_uids) {
		HashSet<Long> liked_links = new HashSet<Long>();
		for (Long uid : app_user_uids) {
			Set<Long> uid_liked_links = _uid2all_passive_linkids_likes.get(uid);
			if (uid_liked_links != null)
				liked_links.addAll(uid_liked_links);
		}
		return liked_links;
	}

	public static ArrayList<Long> getLinksSortedByPopularity(int freq_threshold) throws SQLException {

		String userQuery = "select link_id, count(*) from linkrLinkLikes group by link_id order by count(*) desc;";
		Statement statement = SQLUtil.getStatement();
		ArrayList<Long> link_list = new ArrayList<Long>();

		ResultSet result = statement.executeQuery(userQuery);
		while (result.next()) {
			long link_id = result.getLong(1);
			long count   = result.getLong(2);
			if (count >= freq_threshold)
				link_list.add(link_id);
			else 
				break;
			//System.out.println(link_id + ":\t" + count);
		}

		result.close();
		statement.close();

		return link_list;
	}

	public static ArrayList<Long> getAppUserLinksSortedByPopularity(int freq_threshold) throws SQLException {

		String userQuery = "select link_id, count(*) from linkrLinkLikes where uid in (select distinct uid from trackRecommendedLinks) group by link_id order by count(*) desc;";
		Statement statement = SQLUtil.getStatement();
		ArrayList<Long> link_list = new ArrayList<Long>();

		ResultSet result = statement.executeQuery(userQuery);
		while (result.next()) {
			long link_id = result.getLong(1);
			long count   = result.getLong(2);
			if (count >= freq_threshold)
				link_list.add(link_id);
			else 
				break;
			//System.out.println(link_id + ":\t" + count);
		}

		result.close();
		statement.close();

		return link_list;
	}

	/*
	 * Write arff header data
	 */
	public static String[] demographics_types = new String[]{"isMale","isFemale","sameGender","sameBirthRange","sameLocale"};
	public static String[] group_types = new String[]{"sameGroupMembership"};
	public static String[] user_traits = {"linkrActivities", "linkrBooks", "linkrFavoriteAthletes", "linkrFavoriteTeams", "linkrInspirationalPeople", "linkrInterests", "linkrMovies", "linkrMusic", "linkrSports", "linkrTelevision", "linkrSchoolWith", "linkrWorkWith"};
	public static String[] conversation_types_header = new String[]{"topNWordsSent","topNWordsReceived"};	

	public static void writeHeader(String fileName) throws Exception {
		System.out.println("Writing to " + fileName);
		_writer = new PrintWriter(fileName);		
		_writer.println("@relation app-data");
		_writer.println("@attribute 'Uid' numeric");
		_writer.println("@attribute 'Item' numeric");
		_writer.println("@attribute 'Class' { 'n' , 'y' }");
		for (int feat_index = 0; feat_index < _featuresInt.size(); feat_index++) {
			_writer.println("@attribute '" + 
					_featuresDir.get(feat_index) + "_" + 
					_featuresInt.get(feat_index) + "' { 'n', 'y' }");
		}		

		for (String demographic : demographics_types){
			_writer.println("@attribute 'demographic_" + demographic +  "' { " + NO + ", " + YES + " }");
		}

		for (String group : group_types){
			_writer.println("@attribute 'group_" + group +  "' { " + NO + ", " + YES + " }");
		}

		for (String trait : user_traits){
			_writer.println("@attribute 'trait_" + trait +  "' { " + NO + ", " + YES + " }");
		}

		for (String conversation : conversation_types_header){
			_writer.println("@attribute 'conversation_" + conversation +  "' { " + NO + ", " + YES + " }");
		}        

		_writer.println("@data");
	}

	/*
	 * Write known rating data
	 */
	public static void writeData(String filename) throws Exception {

		writeHeader(filename);

		long yes_ratings = 0;
		long no_ratings  = 0;
		long all_ratings = 0;

		for (String rating : RATINGS) {

			Map<Long,Set<Long>> uid2links =	(rating == YES ? _uid2linkids_likes : _uid2linkids_dislikes); 

			for (Entry<Long, Set<Long>> entry : uid2links.entrySet()){
				Long uid = entry.getKey();
				Set<Long> link_ids = entry.getValue();
				//System.out.println("User " + ExtractRelTables.UID_2_NAME.get(uid) + " made " + link_ids.size() + " " + rating + " ratings");
				for (Long link_id : link_ids){

					all_ratings++;
					if (rating == YES)
						yes_ratings++;
					else {
						if (SKIP_NEGATIVES && Math.random() < NEGATIVE_SKIP_PERCENT)
							continue; 
						no_ratings++;
					}

					//_writer.print(uid + "," + link_id + "," + rating);
					StringBuffer columns = new StringBuffer(uid + "," + link_id + "," + rating);

					// Now write columns
					for (int feat_index = 0; feat_index < _featuresInt.size(); feat_index++) {
						//System.out.println("Writing feature: " + _featuresDir.get(feat_index) + "_" + _featuresInt.get(feat_index));
						Set<Long> alter_likes = _int_dir2uid_linkid.get(_featuresInt.get(feat_index)).get(_featuresDir.get(feat_index)).get(uid);
						String feat_value = alter_likes == null ? NO : (alter_likes.contains(link_id) ? YES : NO);
						//_writer.print("," + feat_value);
						columns.append("," + feat_value);
					}
					columns.append(additionalUserColumns(link_id,uid));
					//_writer.println();

					_writer.println(columns.toString());
				}
			}
		}

		double total_ratings = yes_ratings + no_ratings;
		//System.out.println("Number of possible ratings: " + all_ratings);
		//System.out.println("Number of yes ratings: " + yes_ratings + " -- " + (100d*yes_ratings/total_ratings) + "%");
		//System.out.println("Number of no ratings:  " + no_ratings  + " -- " + (100d*no_ratings/total_ratings) + "%");

		_writer.close();
	}

	/*
	 * Extract user data for users who liked a given link
	 */
	static HashMap<Long, Set<Long>> additionalLinkFeatures = new HashMap<Long, Set<Long>>();
	public static void extractLinkFeatures() throws Exception{

		StringBuilder links = new StringBuilder();
		for (Long link : linksSeen){
			links.append(link + ",");
		}

		String linksToGet = links.toString().substring(0, links.length()-1);
		System.out.println("Extracting links info for: (" + linksToGet + ")");

		Statement statement = SQLUtil.getStatement();	
		String query = "select ll.link_id, lu.uid from linkrLinkLikes ll join linkrUser lu where ll.link_id in (" + linksToGet + ") and ll.id=lu.uid;";
		ResultSet result = statement.executeQuery(query);

		Set<Long> ls;
		while (result.next()){
			Long link_id = result.getLong(1);
			Long uid = result.getLong(2);
			usersSeen.add(uid);

			ls = additionalLinkFeatures.get(link_id);
			if (ls == null){
				System.out.println("\t New link with likes: " + link_id);
				ls = new HashSet<Long>();
			}
			ls.add(uid);
			additionalLinkFeatures.put(link_id, ls);			
		}		

		result.close();
		statement.close();

		extractUserFeatures();

	}

	/*
	 * Extract additional user info for each app user
	 */
	static DataGeneratorPassiveActive ap = new DataGeneratorPassiveActive();
	static HashMap<Long,UserStruct> additionalUserFeatures = new HashMap<Long,UserStruct>();
	static String usersToGet;
	public static void extractUserFeatures() throws Exception{						

		StringBuilder users = new StringBuilder();
		for (Long user : usersSeen){
			users.append(user + ",");
		}

		usersToGet = users.toString().substring(0, users.length()-1);
		System.out.println("Extracting users info for: (" + usersToGet + ")");

		Statement statement = SQLUtil.getStatement();	
		String query = "select uid, gender, right(birthday,4), locale from linkrUser where uid in ( " + usersToGet + ");";
		ResultSet result = statement.executeQuery(query);

		while (result.next()){			
			String gender = result.getString(2);
			int birthday = result.getInt(3);
			String locale = result.getString(4);
			UserStruct us = ap.new UserStruct(gender,birthday,locale);
			additionalUserFeatures.put(result.getLong(1), us);

			System.out.println("\t New user data added:" + result.getLong(1));

			//extractGroups(result.getLong(1));
			//extractTraits(result.getLong(1));
			//extractMessages(result.getLong(1));
		}

		result.close();
		statement.close();

		extractGroups();
		extractTraits();
		extractMessages();
	}

	/* 
	 * Extract user gruops info
	 */
	public static void extractGroups() throws Exception {

		System.out.println("\t -> Extracting groups data");

		//mysql> select count(*), id, name from linkrGroups group by id having count(*) > 10 and count(*) < 15 order by count(*) desc;
		String q = "select uid, id from linkrGroups where uid in (" + usersToGet + ");";		
		Statement statement = SQLUtil.getStatement();		
		ResultSet result = statement.executeQuery(q);
		UserStruct us;

		while (result.next()){
			us = additionalUserFeatures.get(result.getLong(1));
			us.groupMemberships.add(result.getLong(2));
		}
		result.close();
		statement.close();
	}

	/*
	 * extract user traits
	 */
	public static void extractTraits() throws Exception{

		System.out.println("\t -> Extracting user traits data");

		UserStruct us;
		Statement statement = SQLUtil.getStatement();
		ResultSet result = null;
		//656856635
		// {"linkrActivities", "linkrBooks", "linkrFavoriteAthletes", "linkrFavoriteTeams", "linkrInspirationalPeople", "linkrInterests", "linkrMovies", "linkrMusic", "linkrSports", "linkrTelevision", "linkrSchoolWith", "linkrWorkWith"};
		for (String trait : user_traits){
			String q;
			if (trait.equals("linkrSchoolWith")){
				q = "select uid1, school_id from " + trait + " where uid1 in (" + usersToGet + ");";
			} else if (trait.equals("linkrWorkWith")){
				q = "select uid1, employer_id from " + trait + " where uid1 in (" + usersToGet + ");";
			} else {
				q = "select uid, id from " + trait + " where uid in (" + usersToGet + ");";
			}

			result = statement.executeQuery(q);

			while (result.next()){
				us = additionalUserFeatures.get(result.getLong(1));
				us.userTraits.get(trait).add(result.getLong(2));
			}
		}
		result.close();
		statement.close();
	}

	/*
	 * Extract user messages
	 */

	public static String[] conversation_types = {"linkrLinkComments","linkrPhotoComments","linkrPostComments","linkrVideoComments"};
	public static void extractMessages() throws Exception{

		System.out.println("\t -> Extracting messages data");

		PredictiveWords.buildMessagesDictionary(false);
		Map<Long,ArrayList<String>> sent = new HashMap<Long,ArrayList<String>>();
		Map<Long,ArrayList<String>> received = new HashMap<Long,ArrayList<String>>();
		StringBuffer base = new StringBuffer("select uid, message from ");
		Statement statement = SQLUtil.getStatement();
		ResultSet result = null;		

		// extract messages info for user
		for (String table : conversation_types){			
			result = statement.executeQuery(base.toString() + table + " where uid in (" + usersToGet + ");"); 	// incoming			
			while (result.next()){
				ArrayList<String> rec = received.get(result.getLong(1));
				if (rec == null)
					rec = new ArrayList<String>();
				rec.add(result.getString(2));
				received.put(result.getLong(1),rec);
			}
			result.close();

			result = statement.executeQuery(base.toString() + table + " where from_id in (" + usersToGet + ");"); // outcoming			
			while (result.next()){			
				ArrayList<String> se = sent.get(result.getLong(1));
				if (se == null)
					se = new ArrayList<String>();
				se.add(result.getString(2));
				sent.put(result.getLong(1),se);
			}
			result.close();

		}

		statement.close();
		// check whether user has mentioned a top word in a sent message
		outer:
			for (Entry<Long, ArrayList<String>> user : sent.entrySet()){
				Long uid = user.getKey();
				UserStruct us = additionalUserFeatures.get(uid);
				for (String needle : topNWords){
					for (String message : user.getValue()){
						for (String word : message.split("\\s+")){
							if (word.equals(needle)){
								us.sentMention = true;
								break outer;
							}
						}
					}
				}
			}

		// check whether user has mentioned a top word in a received message
		outer:
			for (Entry<Long, ArrayList<String>> user : received.entrySet()){
				Long uid = user.getKey();
				UserStruct us = additionalUserFeatures.get(uid);
				for (String needle : topNWords){
					for (String message : user.getValue()){
						for (String word : message.split("\\s+")){
							if (word.equals(needle)){
								us.receivedMention = true;
								break outer;
							}
						}
					}
				}
			}
	}

	/*
	 * build additional columns
	 */

	public static String additionalUserColumns(long link_id, long uid){
		StringBuilder results = new StringBuilder();

		// flags 
		boolean sameGender = false, sameBirthday = false, sameLocale = false, sameGroup = false, 
				sentMention = false, receivedMention = false, sameActivities = false, sameBooks = false, sameFavoriteAthletes = false, 
				sameFavoriteTeams = false, sameInspirationalPeople = false, sameInterests = false, sameMovies = false, 
				sameMusic = false, sameSports = false, sameTelevision = false, sameSchool = false, sameWork = false;

		// user info
		UserStruct userInfo = additionalUserFeatures.get(uid);

		String userGender = userInfo.gender;
		int userBirthday = userInfo.birthday;
		String userLocale = userInfo.locale;

		// user groups
		ArrayList<Long> userGroups = userInfo.groupMemberships;

		// user traits
		HashSet<Long> userActivities = userInfo.userTraits.get("linkrActivities");
		HashSet<Long> userBooks = userInfo.userTraits.get("linkrBooks");
		HashSet<Long> userFavoriteAthletes = userInfo.userTraits.get("linkrFavoriteAthletes");
		HashSet<Long> userFavoriteTeams = userInfo.userTraits.get("linkrFavoriteTeams");
		HashSet<Long> userInspirationalPeople = userInfo.userTraits.get("linkrInspirationalPeople");
		HashSet<Long> userInterests = userInfo.userTraits.get("linkrInterests");
		HashSet<Long> userMovies = userInfo.userTraits.get("linkrMovies");
		HashSet<Long> userMusic = userInfo.userTraits.get("linkrMusic");
		HashSet<Long> userSports = userInfo.userTraits.get("linkrSports");
		HashSet<Long> userTelevision = userInfo.userTraits.get("linkrTelevision");
		HashSet<Long> userSchoolWith = userInfo.userTraits.get("linkrSchoolWith");
		HashSet<Long> userWorksWith = userInfo.userTraits.get("linkrWorkWith");

		// likee info
		Set<Long> usersLiked = additionalLinkFeatures.get(link_id);

		String likeeGender;
		int likeeBirthday;
		String likeeLocale;

		ArrayList<Long> likeeGroups;

		HashSet<Long> likeeActivities, likeeBooks, likeeFavoriteAthletes, likeeFavoriteTeams, likeeInspirationalPeople,
		likeeInterests, likeeMovies, likeeMusic, likeeSports, likeeTelevision, likeeSchoolWith, likeeWorksWith;

		boolean likeeSentMention;
		boolean likeeReceivedMention;

		// likee info
		if (usersLiked != null){ // want at least one person to have liked this
			for (long likeeID : usersLiked){
				// flags already all set
				if (sameGender && sameBirthday && sameLocale && sameGroup && sentMention && receivedMention && 
						sameActivities && sameBooks && sameFavoriteAthletes && 
						sameFavoriteTeams && sameInspirationalPeople && sameInterests && sameMovies && 
						sameMusic && sameSports && sameTelevision && sameSchool && sameWork){
					break;
				}
				// skip self
				if (likeeID == uid){
					continue;
				}

				UserStruct likee = additionalUserFeatures.get(likeeID);

				likeeGender = likee.gender;
				likeeBirthday = likee.birthday;
				likeeLocale = likee.locale;

				likeeGroups = likee.groupMemberships;

				likeeActivities = likee.userTraits.get("linkrActivities");
				likeeBooks = likee.userTraits.get("linkrBooks");
				likeeFavoriteAthletes = likee.userTraits.get("linkrFavoriteAthletes");
				likeeFavoriteTeams = likee.userTraits.get("linkrFavoriteTeams");
				likeeInspirationalPeople = likee.userTraits.get("linkrInspirationalPeople");
				likeeInterests = likee.userTraits.get("linkrInterests");
				likeeMovies = likee.userTraits.get("linkrMovies");
				likeeMusic = likee.userTraits.get("linkrMusic");
				likeeSports = likee.userTraits.get("linkrSports");
				likeeTelevision = likee.userTraits.get("linkrTelevision");
				likeeSchoolWith = likee.userTraits.get("linkrSchoolWith");
				likeeWorksWith = likee.userTraits.get("linkrWorkWith");

				likeeSentMention = likee.sentMention;
				likeeReceivedMention = likee.receivedMention;


				// test whether user and likee's have similarities
				if (!sameGender)
					sameGender = (userGender.equals(likeeGender)) ? true : false;

				if (!sameBirthday){
					int rounded = (userBirthday + 4) / 5 * 5;
					if (likeeBirthday >= (rounded-4) && likeeBirthday <= rounded){
						sameBirthday = true;				
					}
					//System.out.println("\t" + birthday + ":" + (birthday >= (rounded-4) && birthday <= rounded));				
				}				

				if (!sameLocale)
					sameLocale = (userLocale.equals(likeeLocale)) ? true : false;

				if (!sameGroup){
					for (Long group : userGroups){
						if (likeeGroups.contains(group))
							sameGroup = true;
					}
				}

				//traits
				if (!sameActivities)
					sameActivities = sameTraits(userActivities,likeeActivities);

				if (!sameBooks)
					sameBooks = sameTraits(userBooks,likeeBooks);

				if (!sameFavoriteAthletes)
					sameFavoriteAthletes = sameTraits(userFavoriteAthletes,likeeFavoriteAthletes);

				if (!sameFavoriteTeams)
					sameFavoriteTeams = sameTraits(userFavoriteTeams,likeeFavoriteTeams);

				if (!sameInspirationalPeople)
					sameInspirationalPeople = sameTraits(userInspirationalPeople, likeeInspirationalPeople);

				if (!sameInterests)
					sameInterests = sameTraits(userInterests,likeeInterests);

				if (!sameMovies)
					sameMovies = sameTraits(userMovies,likeeMovies);

				if (!sameMusic)
					sameMusic = sameTraits(userMusic,likeeMusic);

				if (!sameSports)
					sameSports = sameTraits(userSports,likeeSports);

				if (!sameTelevision)
					sameTelevision = sameTraits(userTelevision,likeeTelevision);

				if (!sameSchool)
					sameSchool = sameTraits(userSchoolWith,likeeSchoolWith);

				if (!sameWork)
					sameWork = sameTraits(userWorksWith,likeeWorksWith);

				if (!sentMention)
					sentMention = likeeSentMention;

				if (!receivedMention)
					receivedMention = likeeReceivedMention;

			}
		}

		//demographics
		results.append(PRE + ((userGender.equals("male")) ? YES : "," + NO));		// user is male
		results.append(PRE + ((userGender.equals("female")) ? YES : NO));			// user is female
		results.append(PRE + (sameGender ? YES : NO)); 								// same gendered user has(nt) liked link		
		results.append(PRE + (sameBirthday ? YES : NO));							// same birthday range user has(nt) liked link
		results.append(PRE + (sameLocale ? YES : NO));								// same localed user has(nt) liked link

		// groups
		results.append(PRE + (sameGroup ? YES : NO));								// same group membership		

		//traits
		results.append(PRE + (sameActivities ? YES : NO));
		results.append(PRE + (sameBooks ? YES : NO));
		results.append(PRE + (sameFavoriteAthletes ? YES : NO));
		results.append(PRE + (sameFavoriteTeams ? YES : NO));
		results.append(PRE + (sameInspirationalPeople ? YES : NO));
		results.append(PRE + (sameInterests ? YES : NO));
		results.append(PRE + (sameMovies ? YES : NO));
		results.append(PRE + (sameMusic ? YES : NO));
		results.append(PRE + (sameSports ? YES : NO));
		results.append(PRE + (sameTelevision ? YES : NO));
		results.append(PRE + (sameSchool ? YES : NO));
		results.append(PRE + (sameWork ? YES : NO));

		//conversation
		results.append(PRE + ((sentMention ? YES : NO)));							// mentioned top n words in a sent message
		results.append(PRE + ((receivedMention ? YES : NO)));						// mentioned top n words in a received message

		return results.toString();
	}

	/*
	 * return whether two hashsets share the same value
	 */
	public static boolean sameTraits(HashSet<Long> user, HashSet<Long> likee){
		for (long userTrait : user){
			if (likee.contains(userTrait))
				return true;
		}
		return false;
	}

	/*
	 * Store user info
	 */				
	public class UserStruct{
		// demographics store
		String gender;
		int birthday;
		String locale;

		// groups store
		ArrayList<Long> groupMemberships = new ArrayList<Long>();

		// user traits
		HashMap<String, HashSet<Long>> userTraits = new HashMap<String,HashSet<Long>>();

		// conversation content store (whether they have used a top N word)
		boolean sentMention = false;
		boolean receivedMention = false;

		public UserStruct(String gender, int birthday, String locale){
			this.gender = gender;
			this.birthday = birthday;
			this.locale = locale;

			for (String trait : user_traits){
				userTraits.put(trait,new HashSet<Long>());
			}
		}		

		public String toString(){

			StringBuffer gp = new StringBuffer();
			for (Long group : groupMemberships){
				gp.append(group + ",");
			}

			StringBuffer traits = new StringBuffer();
			for (Entry<String, HashSet<Long>> trait : userTraits.entrySet()){
				traits.append("\n\t\t" + trait.getKey() + ":");
				for (Long id : trait.getValue()){
					traits.append(id + ",");
				}
			}

			return "  \n\tGender:" + gender + 
					" \n\tBirthday:" + birthday + 
					" \n\tLocale:" + locale +
					" \n\tGroups:" + gp.toString() +
					" \n\tTraits:" + traits.toString() +
					" \n\tSent Mention:" + sentMention + 
					" \n\tReceived Mention:" + receivedMention;
		}
	}

	/*
	 * Extract birthdays in 5 year sets
	 */
	public static void getDemographicsInfo() throws Exception{
		String query = "select count(*), right(lu.birthday,4) from linkrUser lu where lu.uid in (SELECT distinct uid FROM trackRecommendedLinks) group by right(lu.birthday,4);";
		HashMap<Integer,Integer> bdayRanges = new HashMap<Integer,Integer>();

		Statement statement = SQLUtil.getStatement();		
		ResultSet result = statement.executeQuery(query);

		while (result.next()) {			
			int count = result.getInt(1);
			int year = result.getInt(2);
			int rounded = (year + 4) / 5 * 5;			

			//System.out.println(year + "," + rounded + ":" + count);

			if (bdayRanges.get(rounded) != null){
				bdayRanges.put(rounded, bdayRanges.get(rounded) + count);
			} else {
				bdayRanges.put(rounded, count);
			}	

		}

		for (Entry<Integer, Integer> bday : bdayRanges.entrySet()){
			int range = bday.getKey();
			int count = bday.getValue();

			System.out.println((range-4) + "-" + range + ":" + count);
		}		
	}	

	/*
	 * Extract gruops info
	 */
	public static void getGroupsInfo() throws Exception{			

		int minSize = 1;
		int maxSize = 10;

		//String query = "select count(*), id, name from linkrGroups group by id having count(*) > 10 and count(*) < 15 order by count(*) desc;";
		String query = "select count(*),id,name from linkrGroups where uid in (select distinct uid from trackRecommendedLinks) group by id having count(*) > " + minSize + " and count(*) < " + maxSize + " order by count(*) desc;";

		Statement statement = SQLUtil.getStatement();		
		ResultSet result = statement.executeQuery(query);

		System.out.println("Group ranges " + minSize + " to " + maxSize);
		while (result.next()) {			
			int count = result.getInt(1);
			String name = result.getString(3);			

			System.out.println(count + " - " + name);		

		}
	}

	/*
	 * extract traits info
	 */

	public static void getTraitsInfo() throws Exception{
		//select count(id),name from linkrActivities where uid in (select distinct uid from trackRecommendedLinks) group by id order by count(*) desc limit 10;
		int limit = 15;

		Statement statement = SQLUtil.getStatement();				

		String q = null;
		for (String trait : user_traits){
			if (trait.equals("linkrSchoolWith")){
				continue;
				//q = "select count(school_id),name from " + trait + " where uid1 in (select distinct uid from trackRecommendedLinks) or uid2 in (select distinct uid from trackRecommendedLinks) group by id order by count(*) desc limit " + limit + ";";
			} else if (trait.equals("linkrWorkWith")){				
				continue;
				//q = "select count(employer_id),name from " + trait + " where uid1 in (select distinct uid from trackRecommendedLinks) or uid2 in (select distinct uid from trackRecommendedLinks) group by id order by count(*) desc limit " + limit + ";";
			} else {
				q = "select count(id),name from " + trait + " where uid in (select distinct uid from trackRecommendedLinks) group by id order by count(*) desc limit " + limit + ";";
			}
			System.out.println(trait);
			ResultSet result = statement.executeQuery(q);
			while (result.next()) {			
				int count = result.getInt(1);
				String name = result.getString(2);			
				System.out.println(count + " - " + name);		
			}
			System.out.println();
		}



	}

	public static void main(String[] args) throws Exception {

		populateCachedData(true /* active */);
		//writeData("active_data.arff");
		//populateCachedData(false /* passive */);
		//writeData("passive_data.arff");
		//System.out.println(getAppConversationContent(162631113776237L,670845000));

		//populateCachedData(true);

		//getAppUserFeaturesInfo();
		//extractLinkFeatures(308324665867510L);
		//extractLinkFeatures(204685499600824L);									

		for (Entry<Long, UserStruct> entry : additionalUserFeatures.entrySet()){
			long id = entry.getKey();
			UserStruct results = entry.getValue();
			System.out.println(id + "->" + results);
		}

		//extractMessages(1461424861L);
		//writeData("asd.arff");		

		//getDemographicsInfo();
		//System.out.println();
		//getGroupsInfo();
		//getTraitsInfo();

	}

}