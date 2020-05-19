

package com.borkozic.location;

import com.borkozic.location.ILocationCallback;

interface ILocationRemoteService
{
    void registerCallback(ILocationCallback cb);
    void unregisterCallback(ILocationCallback cb);
    boolean isLocating();
}
