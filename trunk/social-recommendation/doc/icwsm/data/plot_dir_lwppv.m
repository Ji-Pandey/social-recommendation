load_constants;

% init
legend_content = strvcat('Link','Wall Post','Photo','Video','All');
subm = 3;
subn = 4;
fontsize = 10;

% do plots
figure(1);
load likes_data.txt;

% Row 1: INCOMING
do_plot('k', 'P(likes|incoming tags)', likes_data, legend_content, subm, subn, 1, 1, fontsize, ...
    [-1; get_index(AVG,IN,A,POST_TAGS); get_index(AVG,IN,A,PHOTO_TAGS); get_index(AVG,IN,A,VIDEO_TAGS); get_index(AVG,IN,A,ALL_TAGS)]);
do_plot('k', 'P(likes|incoming comments)', likes_data, legend_content, subm, subn, 1, 2, fontsize, ...
    [get_index(AVG,IN,A,LINK_COMMENTS); get_index(AVG,IN,A,POST_COMMENTS); get_index(AVG,IN,A,PHOTO_COMMENTS); get_index(AVG,IN,A,VIDEO_COMMENTS); get_index(AVG,IN,A,ALL_COMMENTS)]);
do_plot('k', 'P(likes|incoming likes)', likes_data, legend_content, subm, subn, 1, 3, fontsize, ...
    [get_index(AVG,IN,A,LINK_LIKES); get_index(AVG,IN,A,POST_LIKES); get_index(AVG,IN,A,PHOTO_LIKES); get_index(AVG,IN,A,VIDEO_LIKES); get_index(AVG,IN,A,ALL_LIKES)]);
do_plot('k', 'P(likes|all incoming)', likes_data, legend_content, subm, subn, 1, 4, fontsize, ...
    [get_index(AVG,IN,A,LINK_ALL); get_index(AVG,IN,A,POST_ALL); get_index(AVG,IN,A,PHOTO_ALL); get_index(AVG,IN,A,VIDEO_ALL); -1]);

% Row 2: OUTGOING
do_plot('k', 'P(likes|outgoing tags)', likes_data, legend_content, subm, subn, 2, 1, fontsize, ...
    [-1; get_index(AVG,OUT,A,POST_TAGS); get_index(AVG,OUT,A,PHOTO_TAGS); get_index(AVG,OUT,A,VIDEO_TAGS); get_index(AVG,OUT,A,ALL_TAGS)]);
do_plot('k', 'P(likes|outgoing comments)', likes_data, legend_content, subm, subn, 2, 2, fontsize, ...
    [get_index(AVG,OUT,A,LINK_COMMENTS); get_index(AVG,OUT,A,POST_COMMENTS); get_index(AVG,OUT,A,PHOTO_COMMENTS); get_index(AVG,OUT,A,VIDEO_COMMENTS); get_index(AVG,OUT,A,ALL_COMMENTS)]);
do_plot('k', 'P(likes|outgoing likes)', likes_data, legend_content, subm, subn, 2, 3, fontsize, ...
    [get_index(AVG,OUT,A,LINK_LIKES); get_index(AVG,OUT,A,POST_LIKES); get_index(AVG,OUT,A,PHOTO_LIKES); get_index(AVG,OUT,A,VIDEO_LIKES); get_index(AVG,OUT,A,ALL_LIKES)]);
do_plot('k', 'P(likes|all outgoing)', likes_data, legend_content, subm, subn, 2, 4, fontsize, ...
    [get_index(AVG,OUT,A,LINK_ALL); get_index(AVG,OUT,A,POST_ALL); get_index(AVG,OUT,A,PHOTO_ALL); get_index(AVG,OUT,A,VIDEO_ALL); -1]);

% Row 3: BIDIR
do_plot('k', 'P(likes|bidir. tags)', likes_data, legend_content, subm, subn, 3, 1, fontsize, ...
    [-1; get_index(AVG,BI,A,POST_TAGS); get_index(AVG,BI,A,PHOTO_TAGS); get_index(AVG,BI,A,VIDEO_TAGS); get_index(AVG,BI,A,ALL_TAGS)]);
do_plot('k', 'P(likes|bidir. comments)', likes_data, legend_content, subm, subn, 3, 2, fontsize, ...
    [get_index(AVG,BI,A,LINK_COMMENTS); get_index(AVG,BI,A,POST_COMMENTS); get_index(AVG,BI,A,PHOTO_COMMENTS); get_index(AVG,BI,A,VIDEO_COMMENTS); get_index(AVG,BI,A,ALL_COMMENTS)]);
do_plot('k', 'P(likes|bidir. likes)', likes_data, legend_content, subm, subn, 3, 3, fontsize, ...
    [get_index(AVG,BI,A,LINK_LIKES); get_index(AVG,BI,A,POST_LIKES); get_index(AVG,BI,A,PHOTO_LIKES); get_index(AVG,BI,A,VIDEO_LIKES); get_index(AVG,BI,A,ALL_LIKES)]);
do_plot('k', 'P(likes|all bidir.)', likes_data, legend_content, subm, subn, 3, 4, fontsize, ...
    [get_index(AVG,BI,A,LINK_ALL); get_index(AVG,BI,A,POST_ALL); get_index(AVG,BI,A,PHOTO_ALL); get_index(AVG,BI,A,VIDEO_ALL); -1]);
