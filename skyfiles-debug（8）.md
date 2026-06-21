2026-06-17 00:06:55 [main] FileListAdapter -> onBindViewHolder - binding item: Music at position 11, payloads: []
2026-06-17 00:06:55 [main] FileListAdapter -> onBindViewHolder - binding item: Notifications at position 12, payloads: []
2026-06-17 00:06:55 [main] FileListAdapter -> onBindViewHolder - binding item: oua_classifier at position 13, payloads: []
2026-06-17 00:06:55 [main] FileListAdapter -> onBindViewHolder - binding item: Pictures at position 14, payloads: []
2026-06-17 00:06:55 [main] FileListAdapter -> onBindViewHolder - binding item: Podcasts at position 15, payloads: []
2026-06-17 00:06:55 [main] FileListAdapter -> onBindViewHolder - binding item: Recordings at position 16, payloads: []
2026-06-17 00:06:55 [main] FileListAdapter -> onBindViewHolder - binding item: Ringtones at position 17, payloads: []
2026-06-17 00:06:55 [main] FileListAdapter -> onBindViewHolder - binding item: Saved Searches at position 18, payloads: []
2026-06-17 00:06:55 [main] FileListAdapter -> onBindViewHolder - binding item: Subtitles at position 19, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: ThemeStore at position 20, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [9EhpVrCd40c] Orgasum (480).mp4 at position 21, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: _.aceself at position 0, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: Alarms at position 1, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: Android at position 2, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: Audiobooks at position 3, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: colour at position 4, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: DCIM at position 5, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: Documents at position 6, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: Download at position 7, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: Files by Google at position 8, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: M&S at position 9, payloads: []
2026-06-17 00:06:56 [main] FileListAdapter -> onBindViewHolder - binding item: Movies at position 10, payloads: []
2026-06-17 00:06:58 [main] MediaCategoryFragment -> openAlbumDetail: album=Unsorted, folderPath=/storage/emulated/0
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: _.aceself at position 0, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: Alarms at position 1, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: Android at position 2, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: Audiobooks at position 3, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: colour at position 4, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: DCIM at position 5, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: Documents at position 6, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: Download at position 7, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: Files by Google at position 8, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: M&S at position 9, payloads: []
2026-06-17 00:06:59 [main] FileListAdapter -> onBindViewHolder - binding item: Movies at position 10, payloads: []
2026-06-17 00:07:04 [AsyncTask #10] SkyFiles -> SHARE ENUMERATION FAILED
2026-06-17 00:07:04 [AsyncTask #10] SkyFiles -> SHARE ENUMERATION FAILED DETAILS
Throwable class: com.hierynomus.smbj.common.SMBRuntimeException
Throwable message: Got interrupted waiting for 1 to be available. Credits available at this moment: 511
Stack Trace:
com.hierynomus.smbj.common.SMBRuntimeException: Got interrupted waiting for 1 to be available. Credits available at this moment: 511
	at com.hierynomus.smbj.connection.SequenceWindow.get(SequenceWindow.java:57)
	at com.hierynomus.smbj.connection.Connection.send(Connection.java:224)
	at com.hierynomus.smbj.session.Session.send(Session.java:303)
	at com.hierynomus.smbj.session.Session.connectTree(Session.java:124)
	at com.hierynomus.smbj.session.Session.connectShare(Session.java:113)
	at com.rapid7.client.dcerpc.transport.SMBTransportFactories.getTransport(SMBTransportFactories.java:55)
	at me.zhanghai.android.files.provider.smb.client.Client.openDirectoryIterator(Client.kt:96)
	at me.zhanghai.android.files.provider.smb.SmbFileSystemProvider.newDirectoryStream(SmbFileSystemProvider.kt:168)
	at java8.nio.file.Files.newDirectoryStream(Files.java:457)
	at me.zhanghai.android.files.provider.common.PathExtensionsKt.newDirectoryStream(PathExtensions.kt:152)
	at me.zhanghai.android.files.filelist.FileListLiveData.loadValue$lambda$0(FileListLiveData.kt:42)
	at me.zhanghai.android.files.filelist.FileListLiveData$$ExternalSyntheticLambda1.call(D8$$SyntheticClass:0)
	at java.util.concurrent.FutureTask.run(FutureTask.java:328)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1100)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:1572)

2026-06-17 00:07:05 [main] FileListAdapter -> onBindViewHolder - binding item: Storage Drive HDD at position 0, payloads: []
2026-06-17 00:07:05 [main] FileListAdapter -> onBindViewHolder - binding item: ZimaOS-HD at position 1, payloads: []
2026-06-17 00:07:06 [main] FileListAdapter -> Item clicked: ZimaOS-HD (position 1)
2026-06-17 00:07:07 [main] FileListAdapter -> onBindViewHolder - binding item: AppData at position 0, payloads: []
2026-06-17 00:07:07 [main] FileListAdapter -> onBindViewHolder - binding item: Backup at position 1, payloads: []
2026-06-17 00:07:07 [main] FileListAdapter -> onBindViewHolder - binding item: Data at position 2, payloads: []
2026-06-17 00:07:07 [main] FileListAdapter -> onBindViewHolder - binding item: Documents at position 3, payloads: []
2026-06-17 00:07:07 [main] FileListAdapter -> onBindViewHolder - binding item: Downloads at position 4, payloads: []
2026-06-17 00:07:10 [main] FileListAdapter -> onBindViewHolder - binding item: Storage Drive HDD at position 0, payloads: []
2026-06-17 00:07:10 [main] FileListAdapter -> onBindViewHolder - binding item: ZimaOS-HD at position 1, payloads: []
2026-06-17 00:07:11 [main] FileListAdapter -> Item clicked: Storage Drive HDD (position 0)
2026-06-17 00:07:11 [main] FileListAdapter -> onBindViewHolder - binding item: Learnings at position 0, payloads: []
2026-06-17 00:07:11 [main] FileListAdapter -> onBindViewHolder - binding item: Media_Library at position 1, payloads: []
2026-06-17 00:07:11 [main] FileListAdapter -> onBindViewHolder - binding item: personal at position 2, payloads: []
2026-06-17 00:07:12 [main] FileListAdapter -> Item clicked: personal (position 2)
2026-06-17 00:07:13 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [9EhpVrCd40c] Orgasum (480).mp4 at position 0, payloads: []
2026-06-17 00:07:13 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [FUBdDEiefUU] Horny Indian Girl With Bf (720).mp4 at position 1, payloads: []
2026-06-17 00:07:13 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [nM3ENprfI7M] Madam Ki Chudai Viral Video (1080).mp4 at position 2, payloads: []
2026-06-17 00:07:13 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [QI5SILl4FEo] Geeli Chut Mein Mota Lund Pel Ke Ladki Ko Jannat Dikhai Indian Desi Hindi Sex MMS Videos Leaked Viral Adult (1080).mp4 at position 3, payloads: []
2026-06-17 00:07:13 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [R2HIL7H9tGt] Indian College Couple Fucking MMS Video (1080).mp4 at position 4, payloads: []
2026-06-17 00:07:13 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [TC7JPoYqi20] Indian hotwife massage (480).mp4 at position 5, payloads: []
2026-06-17 00:07:13 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [TlZNsgVLZ1G] Pankhuri Sex Stranger In Public Viral Video (720)-1.mp4 at position 6, payloads: []
2026-06-17 00:07:13 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [vUCXfNayw7i] Indian wife fucking zomato delivery boy (720).mp4 at position 7, payloads: []
2026-06-17 00:07:13 [main] FileListAdapter -> onBindViewHolder - binding item: EPORNER.COM - [Xh6u3OuekDB] Aditi Mms Indian Mask Girl Hindi Hardcore (720).mp4 at position 8, payloads: []
2026-06-17 00:07:14 [main] FileListAdapter -> Item clicked: EPORNER.COM - [QI5SILl4FEo] Geeli Chut Mein Mota Lund Pel Ke Ladki Ko Jannat Dikhai Indian Desi Hindi Sex MMS Videos Leaked Viral Adult (1080).mp4 (position 3)
2026-06-17 00:07:50 [main] FileListAdapter -> Item clicked: EPORNER.COM - [QI5SILl4FEo] Geeli Chut Mein Mota Lund Pel Ke Ladki Ko Jannat Dikhai Indian Desi Hindi Sex MMS Videos Leaked Viral Adult (1080).mp4 (position 3)
2026-06-17 00:08:04 [main] FileListAdapter -> Item clicked: EPORNER.COM - [9EhpVrCd40c] Orgasum (480).mp4 (position 0)
2026-06-17 00:08:18 [main] FATAL CRASH -> Uncaught exception in thread main
java.lang.IllegalArgumentException: enterPictureInPictureMode: Aspect ratio is too extreme (must be between 0.418410 and 2.390000).
	at android.os.Parcel.createExceptionOrNull(Parcel.java:3373)
	at android.os.Parcel.createException(Parcel.java:3353)
	at android.os.Parcel.readException(Parcel.java:3336)
	at android.os.Parcel.readException(Parcel.java:3278)
	at android.app.IActivityClientController$Stub$Proxy.enterPictureInPictureMode(IActivityClientController.java:2091)
	at android.app.ActivityClient.enterPictureInPictureMode(ActivityClient.java:424)
	at android.app.Activity.enterPictureInPictureMode(Activity.java:3277)
	at me.zhanghai.android.files.video.VideoPlayerActivity.onUserLeaveHint(VideoPlayerActivity.kt:609)
	at android.app.Activity.performUserLeaving(Activity.java:9647)
	at android.app.Instrumentation.callActivityOnUserLeaving(Instrumentation.java:1801)
	at android.app.ActivityThread.performUserLeavingActivity(ActivityThread.java:6349)
	at android.app.ActivityThread.handlePauseActivity(ActivityThread.java:6330)
	at android.app.servertransaction.PauseActivityItem.execute(PauseActivityItem.java:70)
	at android.app.servertransaction.ActivityTransactionItem.execute(ActivityTransactionItem.java:63)
	at android.app.servertransaction.TransactionExecutor.executeLifecycleItem(TransactionExecutor.java:174)
	at android.app.servertransaction.TransactionExecutor.executeTransactionItems(TransactionExecutor.java:106)
	at android.app.servertransaction.TransactionExecutor.execute(TransactionExecutor.java:85)
	at android.app.ActivityThread$H.handleMessage(ActivityThread.java:3131)
	at android.os.Handler.dispatchMessage(Handler.java:115)
	at android.os.Looper.loopOnce(Looper.java:302)
	at android.os.Looper.loop(Looper.java:412)
	at android.app.ActivityThread.main(ActivityThread.java:9998)
	at java.lang.reflect.Method.invoke(Native Method)
	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:613)
	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:1074)
Caused by: android.os.RemoteException: Remote stack trace:
	at com.android.server.wm.ActivityClientController.ensureValidPictureInPictureActivityParams(ActivityClientController.java:1250)
	at com.android.server.wm.ActivityClientController.enterPictureInPictureMode(ActivityClientController.java:1129)
	at android.app.IActivityClientController$Stub.onTransact(IActivityClientController.java:1062)
	at com.android.server.wm.ActivityClientController.onTransact(ActivityClientController.java:184)
	at android.os.Binder.execTransactInternal(Binder.java:1439)


2026-06-17 00:09:17 [main] SkyFilesApplication -> APPLICATION STARTED
2026-06-17 00:09:17 [main] SkyFilesApplication -> Global crash handler installed successfully.
2026-06-17 00:09:18 [AsyncTask #1] SkyFiles -> DNS FAILED
exception=java.net.UnknownHostException: ZIMAOS
2026-06-17 00:09:20 [main] FileListAdapter -> onBindViewHolder - binding item: Storage Drive HDD at position 0, payloads: []
2026-06-17 00:09:20 [main] FileListAdapter -> onBindViewHolder - binding item: ZimaOS-HD at position 1, payloads: []
